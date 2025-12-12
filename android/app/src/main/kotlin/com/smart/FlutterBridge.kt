package com.smart

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.content.ComponentName
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import com.smart.component.SmartToast

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
        const val CHANNEL_UPDATES = "com.smart/update_events"
        const val CHANNEL_TOAST = "com.smart/toast"
    }

    private var eventSink: EventChannel.EventSink? = null
    private var updateSink: EventChannel.EventSink? = null

    fun setup(binaryMessenger: io.flutter.plugin.common.BinaryMessenger) {
        MethodChannel(binaryMessenger, CHANNEL_API).setMethodCallHandler(this)
        MethodChannel(binaryMessenger, CHANNEL_TOAST).setMethodCallHandler { call, result ->
            val msg = call.argument<String>("msg") ?: ""
            when (call.method) {
                "showSuccess" -> {
                    SmartToast.showSuccess(activity, msg)
                    result.success(null)
                }
                "showInfo" -> {
                    SmartToast.showInfo(activity, msg)
                    result.success(null)
                }
                "showFailure" -> {
                    SmartToast.showFailure(activity, msg)
                    result.success(null)
                }
                "showText" -> {
                    SmartToast.showText(activity, msg)
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
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
        EventChannel(binaryMessenger, CHANNEL_UPDATES).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                updateSink = events
                AppUpdateManager.restoreCachedState(appContext)
                scope.launch {
                    AppUpdateManager.updates.collect { status ->
                        launch(Dispatchers.Main) {
                            events?.success(status.toMap())
                        }
                    }
                }
            }

            override fun onCancel(arguments: Any?) {
                updateSink = null
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
            "getDebugLogs" -> {
                val limit = call.argument<Int>("limit") ?: 10
                val offset = call.argument<Int>("offset") ?: 0
                result.success(SmartConfigRepository.getDebugLogs(limit, offset))
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
            "isIgnoringBatteryOptimizations" -> {
                result.success(isIgnoringBatteryOptimizations())
            }
            "requestIgnoreBatteryOptimizations" -> {
                val granted = requestIgnoreBatteryOptimizations()
                result.success(granted)
            }
            "openBatteryOptimizationSettings" -> {
                val ok = openBatteryOptimizationSettings()
                result.success(ok)
            }
            "openAutoStartSettings" -> {
                val ok = openAutoStartSettings()
                result.success(ok)
            }
            "getAppVersion" -> {
                val pkgInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
                result.success(pkgInfo.versionName ?: "0.0.0")
            }
            "checkForUpdate" -> {
                scope.launch(Dispatchers.IO) {
                    val info = AppUpdateManager.fetchLatestRelease()
                    launch(Dispatchers.Main) {
                        if (info != null) {
                            result.success(
                                mapOf(
                                    "version" to info.version,
                                    "downloadUrl" to info.downloadUrl,
                                    "digest" to info.digest
                                )
                            )
                        } else {
                            result.success(null)
                        }
                    }
                }
            }
            "startUpdateDownload" -> {
                val url = call.argument<String>("url")
                val version = call.argument<String>("version")
                val digest = call.argument<String>("digest")
                if (url.isNullOrBlank() || version.isNullOrBlank()) {
                    result.error("INVALID_ARG", "Missing url or version", null)
                } else {
                    AppUpdateManager.startDownload(appContext, url, version, digest)
                    result.success(true)
                }
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

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(appContext.packageName)
    }

    private fun requestIgnoreBatteryOptimizations(): Boolean {
        if (isIgnoringBatteryOptimizations()) return true
        return try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${appContext.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            isIgnoringBatteryOptimizations()
        } catch (e: Exception) {
            // Fallback to settings page if direct request fails
            runCatching {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(intent)
            }
            false
        }
    }

    private fun openBatteryOptimizationSettings(): Boolean {
        val intents = listOf(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${appContext.packageName}")
            }
        )

        intents.forEach { intent ->
            val resolved = appContext.packageManager.queryIntentActivities(intent, 0)
            if (resolved.isNotEmpty()) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return runCatching {
                    activity.startActivity(intent)
                    true
                }.getOrDefault(false)
            }
        }
        return false
    }

    private fun openAutoStartSettings(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val intents = mutableListOf<Intent>()

        when {
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                intents.addAll(
                    listOf(
                        Intent(Intent.ACTION_MAIN).setComponent(
                            ComponentName(
                                "com.huawei.systemmanager",
                                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                            )
                        ),
                        Intent(Intent.ACTION_MAIN).setComponent(
                            ComponentName(
                                "com.huawei.systemmanager",
                                "com.huawei.systemmanager.optimize.process.ProtectActivity"
                            )
                        ),
                        Intent(Intent.ACTION_MAIN).setComponent(
                            ComponentName(
                                "com.hihonor.systemmanager",
                                "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                            )
                        ),
                        Intent(Intent.ACTION_MAIN).setComponent(
                            ComponentName(
                                "com.hihonor.systemmanager",
                                "com.hihonor.systemmanager.optimize.process.ProtectActivity"
                            )
                        )
                    )
                )
            }
            manufacturer.contains("xiaomi") -> intents.add(
                Intent(Intent.ACTION_MAIN).setComponent(
                    ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                )
            )
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> {
                intents.add(
                    Intent(Intent.ACTION_MAIN).setComponent(
                        ComponentName(
                            "com.iqoo.secure",
                            "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
                        )
                    )
                )
                intents.add(
                    Intent(Intent.ACTION_MAIN).setComponent(
                        ComponentName(
                            "com.vivo.permissionmanager",
                            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                        )
                    )
                )
            }
            manufacturer.contains("oppo") || manufacturer.contains("oneplus") || manufacturer.contains("realme") -> {
                intents.add(
                    Intent(Intent.ACTION_MAIN).setComponent(
                        ComponentName(
                            "com.coloros.phonemanager",
                            "com.coloros.phonemanager.startupapp.StartupAppListActivity"
                        )
                    )
                )
                intents.add(
                    Intent(Intent.ACTION_MAIN).setComponent(
                        ComponentName(
                            "com.coloros.safecenter",
                            "com.coloros.safecenter.startupapp.StartupAppListActivity"
                        )
                    )
                )
                intents.add(
                    Intent(Intent.ACTION_MAIN).setComponent(
                        ComponentName(
                            "com.oppo.safe",
                            "com.oppo.safe.permission.startup.StartupAppListActivity"
                        )
                    )
                )
            }
        }

        // Generic fallbacks
        intents.addAll(
            listOf(
                Intent(Intent.ACTION_MAIN).setComponent(
                    ComponentName(
                        "com.letv.android.letvsafe",
                        "com.letv.android.letvsafe.AutobootManageActivity"
                    )
                ),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${appContext.packageName}")
                }
            )
        )

        intents.forEach { intent ->
            val resolved = appContext.packageManager.queryIntentActivities(intent, 0)
            if (resolved.isNotEmpty()) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return runCatching {
                    activity.startActivity(intent)
                    true
                }.getOrDefault(false)
            }
        }
        return false
    }
}
