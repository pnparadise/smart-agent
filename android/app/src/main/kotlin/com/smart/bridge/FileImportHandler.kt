package com.smart.bridge

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.smart.SmartConfigRepository
import com.smart.component.SmartToast
import io.flutter.plugin.common.MethodChannel

class FileImportHandler(
    private val activity: Activity,
    private val appContext: Context
) {
    companion object {
        const val REQ_CODE_IMPORT_TUNNEL = 102
    }

    private var pendingResult: MethodChannel.Result? = null

    fun startImport(result: MethodChannel.Result) {
        pendingResult = result
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/x-wg-config", "text/plain", "application/octet-stream"))
        }
        activity.startActivityForResult(intent, REQ_CODE_IMPORT_TUNNEL)
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQ_CODE_IMPORT_TUNNEL) return false

        val callback = pendingResult
        pendingResult = null
        if (callback == null) return true

        if (resultCode == Activity.RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                handleImportUri(uri, callback)
            } else {
                callback.success(false)
            }
        } else {
            callback.success(false)
        }
        return true
    }

    private fun handleImportUri(uri: Uri, result: MethodChannel.Result) {
        val rawFileName = queryDisplayName(uri) ?: "imported_${System.currentTimeMillis()}.conf"
        val displayName = rawFileName.substringBeforeLast(".")

        val content = runCatching {
            appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("无法读取所选文件")
        }

        content.onSuccess { text ->
            SmartConfigRepository.registerTunnel(displayName, rawFileName, text)
            SmartToast.showSuccess(activity, "已导入隧道配置: $displayName")
            result.success(true)
        }.onFailure {
            SmartToast.showFailure(activity, "导入失败: ${it.message}")
            result.success(false)
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex)
            }
        }
        return null
    }
}
