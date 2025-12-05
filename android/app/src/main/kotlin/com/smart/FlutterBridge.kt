package com.smart

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.Manifest
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.net.wifi.WifiManager

import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

class FlutterBridge(
    private val activity: Activity,
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val onImportConfig: (onComplete: (Boolean) -> Unit) -> Unit,
    private val onToggleVpn: (String, Boolean) -> Unit
) : MethodChannel.MethodCallHandler {

    companion object {
        const val CHANNEL_API = "com.smart/api"
        const val CHANNEL_EVENTS = "com.smart/events"
        const val CHANNEL_MESSAGES = "com.smart/messages"
    }

    private var eventSink: EventChannel.EventSink? = null

    fun setup(binaryMessenger: io.flutter.plugin.common.BinaryMessenger) {
        MethodChannel(binaryMessenger, CHANNEL_API).setMethodCallHandler(this)
        EventChannel(binaryMessenger, CHANNEL_EVENTS).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                eventSink = events
                VpnStateRepository.setUiListening(true)
                // Send current state immediately
                scope.launch {
                    val state = VpnStateRepository.vpnState.value
                    events?.success(stateToMap(state))
                }
            }

            override fun onCancel(arguments: Any?) {
                eventSink = null
                VpnStateRepository.setUiListening(false)
            }
        })
        EventChannel(binaryMessenger, CHANNEL_MESSAGES).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                MessageBridge.sink = events
            }

            override fun onCancel(arguments: Any?) {
                MessageBridge.sink = null
            }
        })

        // Listen to VPN state changes
        scope.launch {
            VpnStateRepository.vpnState.collect { state ->
                launch(Dispatchers.Main) {
                    eventSink?.success(stateToMap(state))
                }
            }
        }
    }

    private fun stateToMap(state: VpnState): Map<String, Any?> {
        return mapOf(
            "isRunning" to state.isRunning,
            "tunnelName" to state.tunnelName,
            "tunnelFile" to state.tunnelFile,
            "lastHandshake" to state.lastHandshake,
            "txBytes" to state.txBytes,
            "rxBytes" to state.rxBytes,
            "appRuleVersion" to state.appRuleVersion
        )
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getTunnels" -> {
                val tunnels = SmartConfigRepository.getTunnels()
                result.success(tunnels)
            }
            "toggleTunnel" -> {
                val file = call.argument<String>("file")
                val active = call.argument<Boolean>("active") ?: false
                if (file != null) {
                    val currentState = VpnStateRepository.vpnState.value
                    val fromTunnel = currentState.tunnelName ?: "直连"
                    
                    val toTunnel = if (active) {
                        SmartConfigRepository.getTunnels().find { it["file"] == file }?.get("name") ?: file
                    } else "直连"
                    
                    SmartConfigRepository.logEvent(
                        SmartConfigRepository.EventType.MANUAL,
                        fromTunnel,
                        toTunnel
                    )
                    
                    onToggleVpn(file, active)
                    result.success(null)
                } else {
                    result.error("INVALID_ARG", "File is null", null)
                }
            }
            "importConfig" -> {
                onImportConfig { success -> result.success(success) }
            }
            "getSmartConfig" -> {
                result.success(
                    configToMap(
                        SmartConfigRepository.agentRuleConfig.value,
                        SmartConfigRepository.appRuleConfig.value
                    )
                )
            }
            "setAgentRuleConfig" -> {
                val enabled = call.argument<Boolean>("enabled") ?: false
                val rulesList = call.argument<List<Map<String, Any>>>("rules") ?: emptyList()
                val rules = rulesList.map { map ->
                    val tunnelFile = map["tunnelFile"] as? String ?: map["tunnelName"] as? String ?: ""
                    val tunnelName = map["tunnelName"] as? String ?: tunnelFile
                    AgentRule(
                        id = map["id"] as String,
                        type = RuleType.valueOf(map["type"] as String),
                        value = map["value"] as String?,
                        tunnelFile = tunnelFile,
                        tunnelName = tunnelName,
                        enabled = map["enabled"] as Boolean
                    )
                }
                SmartConfigRepository.updateAgentRuleConfig(
                    AgentRuleConfig(
                        enabled = enabled,
                        rules = rules
                    )
                )
                result.success(null)
            }
            "setAppRuleConfig" -> {
                val appRuleEnabled = call.argument<Boolean>("appRuleEnabled") ?: false
                val selectedApps = call.argument<List<String>>("selectedApps") ?: emptyList()
                val currentVersion = SmartConfigRepository.appRuleConfig.value.version
                SmartConfigRepository.updateAppRuleConfig(
                    AppRuleConfig(
                        enabled = appRuleEnabled,
                        selectedApps = selectedApps,
                        version = currentVersion
                    )
                )
                result.success(null)
            }
            "getInstalledApps" -> {
                // Load apps asynchronously to avoid blocking UI thread
                scope.launch(Dispatchers.IO) {
                    try {
                        val apps = getInstalledApps()
                        launch(Dispatchers.Main) {
                            result.success(apps)
                        }
                    } catch (e: Exception) {
                        launch(Dispatchers.Main) {
                            result.error("GET_APPS_ERROR", e.message, null)
                        }
                    }
                }
            }
            "getLogs" -> {
                val limit = call.argument<Int>("limit") ?: 10
                val offset = call.argument<Int>("offset") ?: 0
                result.success(SmartConfigRepository.getLogs(limit, offset))
            }
            "clearLogs" -> {
                SmartConfigRepository.clearLogs()
                result.success(null)
            }
            "requestLocationPermission" -> {
                val granted = requestLocationPermission()
                result.success(granted)
            }
            "getSavedSsids" -> {
                result.success(getSavedSsids())
            }
            "getTunnelConfig" -> {
                val file = call.argument<String>("file")
                if (file != null) {
                    result.success(SmartConfigRepository.readTunnelConfig(file))
                } else result.error("INVALID_ARG", "File is null", null)
            }
            "saveTunnelConfig" -> {
                val file = call.argument<String>("file")
                val content = call.argument<String>("content")
                val tunnelName = call.argument<String>("tunnelName")
                if (file != null && content != null) {
                    val ok = SmartConfigRepository.writeTunnelConfig(file, content, tunnelName)
                    result.success(ok)
                } else result.error("INVALID_ARG", "Missing file or content", null)
            }
            "deleteTunnel" -> {
                val file = call.argument<String>("file")
                if (file != null) {
                    val ok = SmartConfigRepository.deleteTunnel(file)
                    result.success(ok)
                } else result.error("INVALID_ARG", "File is null", null)
            }
            else -> result.notImplemented()
        }
    }

    private fun configToMap(agent: AgentRuleConfig, appRule: AppRuleConfig): Map<String, Any> {
        val tunnelDisplayMap = SmartConfigRepository.getTunnels().associate { it["file"]!! to (it["name"] ?: it["file"]!!) }
        return mapOf(
            "enabled" to agent.enabled,
            "appRuleEnabled" to appRule.enabled,
            "selectedApps" to appRule.selectedApps,
            "appRuleVersion" to appRule.version,
            "rules" to agent.rules.map { rule ->
                val display = tunnelDisplayMap[rule.tunnelFile] ?: rule.tunnelName
                mapOf(
                    "id" to rule.id,
                    "type" to rule.type.name,
                    "value" to rule.value,
                    "tunnelFile" to rule.tunnelFile,
                    "tunnelName" to display,
                    "enabled" to rule.enabled
                )
            }
        )
    }

    private fun getInstalledApps(): List<Map<String, Any?>> {
        val pm = appContext.packageManager
        val apps = pm.getInstalledApplications(0)
        return apps.mapNotNull { app ->
            val packageName = app.packageName
            val label = pm.getApplicationLabel(app)?.toString() ?: packageName
            val iconBytes = runCatching { drawableToPng(pm.getApplicationIcon(app)) }.getOrNull()
            mapOf(
                "name" to label,
                "package" to packageName,
                "icon" to iconBytes
            )
        }
    }

    private fun drawableToPng(drawable: Drawable): ByteArray {
        val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
            drawable.bitmap
        } else {
            val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 48
            val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 48
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bmp
        }
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }

    private fun requestLocationPermission(): Boolean {
        val permissions = mutableListOf<String>()
        val fineGranted = hasFineLocation()
        if (!fineGranted) {
            permissions.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, permissions.toTypedArray(), 3001)
        }

        return hasFineLocation() && isLocationEnabled()
    }

    private fun hasFineLocation(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
        }
    }

    private fun getSavedSsids(): List<String> {
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return emptyList()
        val fineGranted = hasFineLocation() && isLocationEnabled()
        if (!fineGranted) return emptyList()

        // Trigger a fresh scan to increase likelihood of results on newer Android versions.
        runCatching { wifiManager.startScan() }

        val nearby = runCatching { wifiManager.scanResults ?: emptyList() }.getOrDefault(emptyList())
            .mapNotNull { it.SSID?.removeSurrounding("\"") }
            .filter { it.isNotBlank() && it != "<unknown ssid>" }

        val configs = runCatching { wifiManager.configuredNetworks ?: emptyList() }.getOrDefault(emptyList())
            .mapNotNull { it.SSID?.removeSurrounding("\"") }
            .filter { it.isNotBlank() && it != "<unknown ssid>" }

        val currentSsid = runCatching { wifiManager.connectionInfo?.ssid?.removeSurrounding("\"") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }

        return (nearby + configs + listOfNotNull(currentSsid)).distinct()
    }
}
