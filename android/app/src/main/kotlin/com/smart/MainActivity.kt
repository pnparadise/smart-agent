package com.smart

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import com.smart.component.SmartToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.IOException

class MainActivity : FlutterActivity() {
    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var flutterBridge: FlutterBridge
    private val importTunnelRequest = 102
    private var pendingImportCallback: ((Boolean) -> Unit)? = null
    private var pendingTunnelFile: String? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        SmartToast.attach(this)
        flutterBridge = FlutterBridge(
            activity = this,
            appContext = applicationContext,
            scope = bridgeScope,
            onImportConfig = { callback ->
                pendingImportCallback = callback
                importTunnelConfig()
            },
            onToggleVpn = { file, active ->
                if (active) {
                    pendingTunnelFile = file
                    val prepareIntent = VpnService.prepare(this)
                    if (prepareIntent != null) {
                        startActivityForResult(prepareIntent, VPN_REQUEST_CODE)
                    } else {
                        SmartRuleManager.toggleManualTunnel(file, true)
                    }
                } else {
                    SmartRuleManager.toggleManualTunnel(file, false)
                }
            }
        )
        flutterBridge.setup(flutterEngine.dartExecutor.binaryMessenger)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPostNotificationPermissionIfNeeded()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.clear()
    }

    override fun onDestroy() {
        bridgeScope.cancel()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                val file = pendingTunnelFile
                pendingTunnelFile = null
                if (file != null) {
                    SmartRuleManager.toggleManualTunnel(file, true)
                } else {
                    startService(Intent(this, SmartAgentVpnService::class.java))
                }
            } else {
                pendingTunnelFile = null
                SmartToast.showFailure(this, "VPN 权限未授予")
            }
        } else if (requestCode == importTunnelRequest) {
            val callback = pendingImportCallback
            pendingImportCallback = null
            if (resultCode == RESULT_OK) {
                data?.data?.let { uri ->
                    importTunnelFromUri(uri, callback)
                } ?: callback?.invoke(false)
            } else {
                callback?.invoke(false)
            }
        }
    }

    companion object {
        private const val VPN_REQUEST_CODE = 101
        private const val NOTIFICATION_REQUEST_CODE = 201
    }

    private fun importTunnelConfig() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/x-wg-config", "text/plain", "application/octet-stream"))
        }
        startActivityForResult(intent, importTunnelRequest)
    }

    private fun importTunnelFromUri(uri: Uri, callback: ((Boolean) -> Unit)?) {
        val rawFileName = queryDisplayName(uri) ?: "imported_${System.currentTimeMillis()}.conf"
        val displayName = rawFileName.substringBeforeLast(".")

        val content = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("无法读取所选文件")
        }

        content.onSuccess { text ->
            SmartConfigRepository.registerTunnel(displayName, rawFileName, text)
            SmartToast.showSuccess(this, "已导入隧道配置: $displayName")
            callback?.invoke(true)
        }.onFailure {
            SmartToast.showFailure(this, "导入失败: ${it.message}")
            callback?.invoke(false)
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex)
            }
        }
        return null
    }

    private fun requestPostNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            NOTIFICATION_REQUEST_CODE
        )
    }
}
