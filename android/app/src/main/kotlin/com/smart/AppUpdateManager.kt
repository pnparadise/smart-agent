package com.smart

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object AppUpdateManager {
    private const val TAG = "AppUpdateManager"
    private const val PREFS_NAME = "app_update"
    private const val KEY_LAST_DOWNLOADED_VERSION = "last_downloaded_version"
    private const val KEY_LAST_DOWNLOADED_DIGEST = "last_downloaded_digest"
    data class ReleaseInfo(val version: String, val downloadUrl: String, val digest: String?)

    enum class UpdateState { IDLE, DOWNLOADING, INSTALLING, ERROR }

    data class UpdateStatus(
        val state: UpdateState = UpdateState.IDLE,
        val progress: Int = 0,
        val latestVersion: String? = null,
        val message: String? = null
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "state" to state.name.lowercase(),
            "progress" to progress,
            "latestVersion" to latestVersion,
            "message" to message
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _updates = MutableStateFlow(UpdateStatus())
    val updates: StateFlow<UpdateStatus> = _updates

    private var downloadJob: Job? = null

    suspend fun fetchLatestRelease(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val url = URL("https://api.github.com/repos/pnparadise/smart-agent/releases/latest")
        val conn = (url.openConnection() as? HttpURLConnection) ?: return@withContext null
        return@withContext try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/json")
            conn.requestMethod = "GET"

            val code = conn.responseCode
            if (code !in 200..299) return@withContext null

            val body = conn.inputStream.use { it.readBytes().decodeToString() }
            val json = JSONObject(body)
            val version = json.optString("name")
            val assets = json.optJSONArray("assets")
            val firstAsset = assets?.optJSONObject(0)
            val downloadUrl = firstAsset?.optString("browser_download_url")
            val digest = firstAsset?.optString("digest") ?: json.optString("digest")

            if (version.isNotBlank() && !downloadUrl.isNullOrBlank()) {
                ReleaseInfo(version, downloadUrl, digest.takeIf { it.isNotBlank() })
            } else null
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    fun startDownload(context: Context, downloadUrl: String, version: String, expectedDigest: String?) {
        downloadJob?.cancel()
        findCachedApk(context, version, expectedDigest)?.let { cached ->
            // Already downloaded; surface ready state and invoke installer without re-downloading
            _updates.value = UpdateStatus(UpdateState.IDLE, 100, version, null)
            installApk(context, cached)
            return
        }
        _updates.value = UpdateStatus(UpdateState.DOWNLOADING, 0, version, null)

        downloadJob = scope.launch {
            try {
                val file = downloadApk(context, downloadUrl, version, expectedDigest)
                if (file != null) {
                    saveDownloadedVersion(context, version, expectedDigest)
                    _updates.value = UpdateStatus(UpdateState.INSTALLING, 100, version, null)
                    installApk(context, file)
                    // Clear active state so Flutter UI stops spinning at 100% while the system installer runs
                    _updates.value = UpdateStatus(UpdateState.IDLE, 100, version, null)
                } else {
                    _updates.value = UpdateStatus(UpdateState.ERROR, 0, version, "Download failed")
                    openReleasesPage(context)
                }
            } catch (e: Exception) {
                _updates.value = UpdateStatus(UpdateState.ERROR, 0, version, e.message)
                openReleasesPage(context)
            }
        }
    }

    private suspend fun downloadApk(context: Context, downloadUrl: String, version: String, expectedDigest: String?): File? =
        withContext(Dispatchers.IO) {
            val url = URL(downloadUrl)
            val conn = (url.openConnection() as? HttpURLConnection) ?: return@withContext null
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.requestMethod = "GET"
            conn.doInput = true

            return@withContext try {
                val responseCode = conn.responseCode
                if (responseCode !in 200..299) {
                    openReleasesPage(context)
                    return@withContext null
                }
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1L

                val targetDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
                if (!targetDir.exists()) targetDir.mkdirs()
                val targetFile = File(targetDir, "smart-agent-$version.apk")

                conn.inputStream.use { input ->
                    targetFile.outputStream().use { output ->
                        copyWithProgress(input, output, total) { percent ->
                            _updates.value = UpdateStatus(UpdateState.DOWNLOADING, percent, version, null)
                        }
                    }
                }
                if (expectedDigest != null && !matchesDigest(targetFile, expectedDigest)) {
                    targetFile.delete()
                    return@withContext null
                }
                targetFile
            } catch (e: Exception) {
                openReleasesPage(context)
                null
            } finally {
                conn.disconnect()
            }
        }

    fun restoreCachedState(context: Context) {
        val version = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_DOWNLOADED_VERSION, null)
            ?: return
        val expectedDigest = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_DOWNLOADED_DIGEST, null)
        val file = findCachedApk(context, version, expectedDigest) ?: return
        _updates.value = UpdateStatus(UpdateState.IDLE, 100, version, null)
    }

    private fun copyWithProgress(
        input: InputStream,
        output: java.io.OutputStream,
        totalBytes: Long,
        onProgress: (Int) -> Unit
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var downloaded = 0L
        var read: Int
        var lastProgress = -1
        while (true) {
            read = input.read(buffer)
            if (read == -1) break
            output.write(buffer, 0, read)
            downloaded += read
            if (totalBytes > 0) {
                val progress = ((downloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
                if (progress != lastProgress) {
                    lastProgress = progress
                    onProgress(progress)
                }
            }
        }
        if (lastProgress < 100 && totalBytes > 0) {
            onProgress(100)
        }
    }

    private fun saveDownloadedVersion(context: Context, version: String, digest: String?) {
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        editor.putString(KEY_LAST_DOWNLOADED_VERSION, version)
        if (!digest.isNullOrBlank()) {
            editor.putString(KEY_LAST_DOWNLOADED_DIGEST, digest)
        } else {
            editor.remove(KEY_LAST_DOWNLOADED_DIGEST)
        }
        editor.apply()
    }

    private fun findCachedApk(context: Context, version: String, expectedDigest: String?): File? {
        val targetName = "smart-agent-$version.apk"
        val candidateDirs = listOfNotNull(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            context.cacheDir
        )
        return candidateDirs
            .map { File(it, targetName) }
            .firstOrNull { file ->
                if (!file.exists() || file.length() <= 0) return@firstOrNull false
                expectedDigest == null || matchesDigest(file, expectedDigest)
            }
    }

    private fun installApk(context: Context, file: File) {
        val authority = "${context.packageName}.fileprovider"
        val apkUri = FileProvider.getUriForFile(context, authority, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    private fun openReleasesPage(context: Context) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/pnparadise/smart-agent/releases"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private fun matchesDigest(file: File, expectedDigestRaw: String): Boolean {
        val expected = normalizeDigest(expectedDigestRaw)
        val actual = computeSha256(file) ?: return false
        return expected.equals(actual, ignoreCase = true)
    }

    private fun normalizeDigest(value: String): String {
        val trimmed = value.trim()
        val withoutPrefix = if (trimmed.lowercase().startsWith("sha256:")) {
            trimmed.substringAfter(":", "")
        } else trimmed
        return withoutPrefix.lowercase()
    }

    private fun computeSha256(file: File): String? {
        return runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    md.update(buffer, 0, read)
                }
            }
            md.digest().joinToString("") { b -> "%02x".format(b) }
        }.getOrNull()
    }
}
