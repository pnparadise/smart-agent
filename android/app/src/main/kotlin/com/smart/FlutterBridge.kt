package com.smart

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.smart.bridge.AppListHandler
import com.smart.bridge.FileImportHandler
import com.smart.bridge.SystemSettingsHandler
import com.smart.bridge.VpnControlHandler
import com.smart.component.SmartToast
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class FlutterBridge(
    private val activity: Activity,
    private val appContext: Context,
    private val scope: CoroutineScope
) : MethodChannel.MethodCallHandler {

    companion object {
        const val CHANNEL_API = "com.smart/api"
        const val CHANNEL_EVENTS = "com.smart/events"
        const val CHANNEL_UPDATES = "com.smart/update_events"
        const val CHANNEL_TOAST = "com.smart/toast"
    }

    private var eventSink: EventChannel.EventSink? = null
    private var updateSink: EventChannel.EventSink? = null

    private val fileHandler = FileImportHandler(activity, appContext)
    private val appHandler = AppListHandler(appContext)
    private val systemHandler = SystemSettingsHandler(activity, appContext)
    private val vpnHandler = VpnControlHandler(activity, appContext)

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
                scope.launch(Dispatchers.Main) {
                    runCatching {
                        val state = VpnStateRepository.vpnState.value
                        events?.success(stateToMap(state))
                    }
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
            "getVpnState" -> {
                runCatching {
                    val state = VpnStateRepository.vpnState.value
                    result.success(stateToMap(state))
                }.onFailure { e ->
                    result.error("STATE_ERROR", e.message, null)
                }
            }
            "getTunnels" -> {
                result.success(SmartConfigRepository.getTunnels())
            }
            "toggleTunnel" -> {
                val file = call.argument<String>("file")
                val active = call.argument<Boolean>("active") ?: false
                if (file != null) {
                    vpnHandler.toggleTunnel(file, active)
                    result.success(null)
                } else {
                    result.error("INVALID_ARG", "File is null", null)
                }
            }
            "importConfig" -> {
                fileHandler.startImport(result)
            }
            "getSmartConfig" -> {
                val agent = SmartConfigRepository.agentRuleConfig.value
                val appRule = SmartConfigRepository.appRuleConfig.value
                val doh = SmartConfigRepository.dohConfig.value
                result.success(
                    mapOf(
                        "agentRuleConfig" to mapOf(
                            "enabled" to agent.enabled,
                            "rules" to agent.rules.map { rule ->
                                mapOf(
                                    "id" to rule.id,
                                    "type" to rule.type.name,
                                    "value" to rule.value,
                                    "tunnelFile" to rule.tunnelFile,
                                    "tunnelName" to rule.tunnelName,
                                    "enabled" to rule.enabled
                                )
                            }
                        ),
                        "appRuleConfig" to mapOf(
                            "enabled" to appRule.enabled,
                            "selectedApps" to appRule.selectedApps,
                            "version" to appRule.version
                        ),
                        "dohConfig" to mapOf(
                            "enabled" to doh.enabled,
                            "dohUrl" to doh.dohUrl
                        )
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
            "getDohConfig" -> {
                val doh = SmartConfigRepository.dohConfig.value
                result.success(mapOf("enabled" to doh.enabled, "dohUrl" to doh.dohUrl))
            }
            "setDohConfig" -> {
                val enabled = call.argument<Boolean>("enabled") ?: false
                val dohUrl = call.argument<String>("dohUrl") ?: ""
                SmartConfigRepository.updateDohConfig(DohConfig(enabled = enabled, dohUrl = dohUrl))
                result.success(null)
            }
            "getInstalledApps" -> {
                appHandler.getInstalledApps(scope, result)
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
                result.success(systemHandler.requestLocationPermission())
            }
            "getSavedSsids" -> {
                result.success(systemHandler.getSavedSsids())
            }
            "getCurrentGatewayIp" -> {
                result.success(SmartRuleManager.getCurrentGatewayIp())
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
                result.success(systemHandler.isIgnoringBatteryOptimizations())
            }
            "requestIgnoreBatteryOptimizations" -> {
                result.success(systemHandler.requestIgnoreBatteryOptimizations())
            }
            "openBatteryOptimizationSettings" -> {
                result.success(systemHandler.openBatteryOptimizationSettings())
            }
            "openAutoStartSettings" -> {
                result.success(systemHandler.openAutoStartSettings())
            }
            "getAppVersion" -> {
                result.success(systemHandler.getAppVersion())
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

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (fileHandler.handleActivityResult(requestCode, resultCode, data)) return true
        if (vpnHandler.handleActivityResult(requestCode, resultCode, data)) return true
        return false
    }
}
