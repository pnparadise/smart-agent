package com.smart

import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.lifecycle.lifecycleScope
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException

class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.smart/vpn"
    private var methodChannel: MethodChannel? = null
    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var flutterBridge: FlutterBridge
    private val importTunnelRequest = 102
    private var pendingImportCallback: ((Boolean) -> Unit)? = null
    private var pendingTunnelFile: String? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        methodChannel?.setMethodCallHandler { call, result ->
            when (call.method) {
                "getTunnelList" -> {
                    result.success(getTunnelList())
                }
                "startVpnService" -> {
                    startVpnService()
                    result.success(null)
                }
                // In this architecture, start/stop are handled automatically by SmartAgent
                // based on network state. Manual start/stop could be added if needed.
                else -> result.notImplemented()
            }
        }

        // Initialize smart rules and bridge for Method/Event channels used by Flutter.
        SmartRuleManager.init(applicationContext)
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
                        SmartConfigRepository.toggleManualTunnel(file, true)
                    }
                } else {
                    SmartConfigRepository.toggleManualTunnel(file, false)
                }
            }
        )
        flutterBridge.setup(flutterEngine.dartExecutor.binaryMessenger)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Observe VPN state changes and send them to Flutter
        lifecycleScope.launch {
            VpnStateRepository.vpnState.collect { vpnState ->
                val stateMap = mapOf(
                    "isRunning" to vpnState.isRunning,
                    "tunnelName" to vpnState.tunnelName
                )
                methodChannel?.invokeMethod("onVpnStateChanged", stateMap)
            }
        }
    }

    override fun onDestroy() {
        bridgeScope.cancel()
        super.onDestroy()
    }

    private fun startVpnService() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            startActivityForResult(vpnIntent, VPN_REQUEST_CODE)
        } else {
            onActivityResult(VPN_REQUEST_CODE, RESULT_OK, null)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                val file = pendingTunnelFile
                pendingTunnelFile = null
                if (file != null) {
                    SmartConfigRepository.toggleManualTunnel(file, true)
                } else {
                    startService(Intent(this, SmartAgent::class.java))
                }
            } else {
                pendingTunnelFile = null
                MessageBridge.send("VPN 权限未授予")
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

    private fun getTunnelList(): List<Map<String, Any>> {
        val tunnelList = mutableListOf<Map<String, Any>>()
        try {
            val configFiles = assets.list("") ?: return emptyList()
            for (fileName in configFiles) {
                try {
                    val lines = assets.open(fileName).bufferedReader().readLines()
                    var name: String? = fileName.substringBeforeLast(".")
                    lines.forEach { line ->
                        val trimmedLine = line.trim()
                        if (trimmedLine.startsWith("#")) {
                            val comment = trimmedLine.substring(1).trim()
                            val parts = comment.split("=", limit = 2).map { it.trim() }
                            if (parts.size == 2 && parts[0].equals("NAME", ignoreCase = true)) {
                                name = parts[1]
                            }
                        }
                    }
                    name?.let { tunnelList.add(mapOf("name" to it, "file" to fileName)) }
                } catch (e: IOException) {
                    continue
                }
            }
        } catch (e: IOException) {
            // Failed to list assets
        }
        return tunnelList
    }

    companion object {
        private const val VPN_REQUEST_CODE = 101
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
            MessageBridge.send("已导入隧道配置: $displayName")
            SmartConfigRepository.registerTunnel(displayName, rawFileName, text)
            callback?.invoke(true)
        }.onFailure {
            MessageBridge.send("导入失败: ${it.message}")
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
}
