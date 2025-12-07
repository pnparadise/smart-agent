package com.smart

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.util.UUID

object SmartConfigRepository {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private lateinit var db: SmartConfigDb
    private lateinit var appContext: Context

    private val _agentRuleConfig = MutableStateFlow(AgentRuleConfig())
    val agentRuleConfig: StateFlow<AgentRuleConfig> = _agentRuleConfig.asStateFlow()
    private val _appRuleConfig = MutableStateFlow(AppRuleConfig())
    val appRuleConfig: StateFlow<AppRuleConfig> = _appRuleConfig.asStateFlow()

    private fun saveAgentConfig() {
        runCatching { db.saveAgentConfig(_agentRuleConfig.value, json) }.onFailure { it.printStackTrace() }
    }

    private fun saveAppRuleConfig() {
        runCatching { db.saveAppRuleConfig(_appRuleConfig.value, json) }.onFailure { it.printStackTrace() }
    }

    private fun notifyRuleChange(eventType: EventType? = EventType.RULE_UPDATED) {
        saveAgentConfig()
        if (eventType != null) SmartRuleManager.onConfigUpdated(eventType)
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        db = SmartConfigDb(appContext)
        load()
    }

    private fun load() {
        val (agent, appRule) = runCatching { db.load(json) }.getOrDefault(AgentRuleConfig() to AppRuleConfig())
        _agentRuleConfig.value = agent
        _appRuleConfig.value = appRule
    }

    fun registerTunnel(name: String, file: String, content: String) {
        val displayName = if (name.contains(".")) name.substringBeforeLast(".") else name
        writeTunnelFile(file, content)
        db.upsertTunnel(displayName, file, content)
    }

    fun getTunnels(): List<Map<String, String>> {
        val tunnelsDir = java.io.File(appContext.filesDir, "tunnels").apply { if (!exists()) mkdirs() }
        val records = db.listTunnelRecords().toMutableList()

        // Backfill DB from disk if needed (legacy files without DB rows).
        val existingFiles = tunnelsDir.listFiles().orEmpty()
        for (file in existingFiles) {
            val alreadyTracked = records.any { it.file == file.name }
            if (!alreadyTracked) {
                val content = runCatching { file.readText() }.getOrDefault("")
                val displayName = file.name.substringBeforeLast(".", file.name)
                db.upsertTunnel(displayName, file.name, content)
            }
        }

        val syncedRecords = db.listTunnelRecords()

        // Ensure physical files exist for all DB rows (backend still consumes files).
        syncedRecords.forEach { record ->
            val target = java.io.File(tunnelsDir, record.file)
            if (!target.exists()) {
                runCatching { target.writeText(record.content) }
            }
        }

        return syncedRecords.map { record ->
            mapOf("name" to record.name, "file" to record.file)
        }
    }

    fun deleteTunnel(fileName: String): Boolean {
        val tunnelsDir = java.io.File(appContext.filesDir, "tunnels").apply { if (!exists()) mkdirs() }
        val target = java.io.File(tunnelsDir, fileName)
        // remove file if present, ignore result
        runCatching { target.delete() }
        db.deleteTunnel(fileName)
        // Remove rules pointing to this tunnel.
        val filteredRules = _agentRuleConfig.value.rules.filter { it.tunnelFile != fileName }
        _agentRuleConfig.value = _agentRuleConfig.value.copy(rules = filteredRules)
        saveAgentConfig()
        return true
    }

    fun readTunnelConfig(fileName: String): String? {
        return db.getTunnelContent(fileName)
            ?: runCatching {
                val tunnelsDir = java.io.File(appContext.filesDir, "tunnels").apply { if (!exists()) mkdirs() }
                val target = java.io.File(tunnelsDir, fileName)
                target.readText().also { content ->
                    val displayName = fileName.substringBeforeLast(".", fileName)
                    db.upsertTunnel(displayName, fileName, content)
                }
            }.getOrNull()
    }

    fun writeTunnelConfig(fileName: String, content: String, tunnelName: String?): Boolean {
        val displayName = if (!tunnelName.isNullOrBlank()) {
            tunnelName.substringBeforeLast(".", tunnelName)
        } else {
            fileName.substringBeforeLast(".", fileName)
        }
        return runCatching {
            writeTunnelFile(fileName, content)
            db.upsertTunnel(displayName, fileName, content)
            true
        }.getOrDefault(false)
    }

    private fun writeTunnelFile(fileName: String, content: String) {
        val tunnelsDir = java.io.File(appContext.filesDir, "tunnels").apply { if (!exists()) mkdirs() }
        val target = java.io.File(tunnelsDir, fileName)
        target.writeText(content)
    }

    fun setEnabled(enabled: Boolean) {
        val current = _agentRuleConfig.value
        if (current.enabled == enabled) return
        _agentRuleConfig.value = current.copy(enabled = enabled)
        notifyRuleChange(if (enabled) EventType.APP_START else null)
    }

    fun updateAgentRuleConfig(config: AgentRuleConfig, triggerEvent: Boolean = true) {
        val previous = _agentRuleConfig.value
        if (previous == config) return
        _agentRuleConfig.value = config
        if (triggerEvent) {
            val event = if (!previous.enabled && config.enabled) EventType.APP_START else EventType.RULE_UPDATED
            notifyRuleChange(event)
        } else {
            saveAgentConfig()
        }
    }

    fun updateAppRuleConfig(config: AppRuleConfig) {
        val previous = _appRuleConfig.value
        if (previous == config) return
        val bumped = if (previous.selectedApps != config.selectedApps) {
            config.copy(version = previous.version + 1)
        } else config.copy(version = previous.version)
        _appRuleConfig.value = bumped
        saveAppRuleConfig()
        maybeRestartVpnForAppRule(bumped)
    }

    fun addRule(type: RuleType, value: String?, tunnelFile: String, tunnelName: String?) {
        val newRule = AgentRule(
            id = UUID.randomUUID().toString(),
            type = type,
            value = value,
            tunnelFile = tunnelFile,
            tunnelName = tunnelName ?: tunnelFile
        )
        val newRules = _agentRuleConfig.value.rules + newRule
        _agentRuleConfig.value = _agentRuleConfig.value.copy(rules = newRules)
        notifyRuleChange()
    }

    fun updateRule(rule: AgentRule) {
        val newRules = _agentRuleConfig.value.rules.map { if (it.id == rule.id) rule else it }
        _agentRuleConfig.value = _agentRuleConfig.value.copy(rules = newRules)
        notifyRuleChange()
    }

    fun deleteRule(ruleId: String) {
        val newRules = _agentRuleConfig.value.rules.filter { it.id != ruleId }
        _agentRuleConfig.value = _agentRuleConfig.value.copy(rules = newRules)
        notifyRuleChange()
    }
    
    fun moveRule(fromIndex: Int, toIndex: Int) {
        val rules = _agentRuleConfig.value.rules.toMutableList()
        if (fromIndex in rules.indices && toIndex in rules.indices) {
            val item = rules.removeAt(fromIndex)
            rules.add(toIndex, item)
            _agentRuleConfig.value = _agentRuleConfig.value.copy(rules = rules)
            notifyRuleChange()
        }
    }

    fun setAppRuleEnabled(enabled: Boolean) {
        val previous = _appRuleConfig.value
        if (previous.enabled == enabled) return
        val newConfig = previous.copy(enabled = enabled)
        _appRuleConfig.value = newConfig
        saveAppRuleConfig()
        maybeRestartVpnForAppRule(newConfig)
    }

    fun updateSelectedApps(packages: List<String>) {
        val previous = _appRuleConfig.value
        if (previous.selectedApps == packages) return
        val newVersion = previous.version + 1
        val newConfig = previous.copy(selectedApps = packages, version = newVersion)
        _appRuleConfig.value = newConfig
        saveAppRuleConfig()
        maybeRestartVpnForAppRule(newConfig)
    }

    fun toggleManualTunnel(targetFile: String?, active: Boolean) {
        val tunnels = getTunnels()
        val resolvedFile = when {
            active && !targetFile.isNullOrBlank() -> targetFile
            active -> tunnels.firstOrNull()?.get("file")
            else -> null
        }

        val currentState = VpnStateRepository.vpnState.value
        val fromTunnel = currentState.tunnelName ?: "直连"
        val toTunnel = if (active) {
            resolvedFile?.let { file ->
                tunnels.firstOrNull { it["file"] == file }?.get("name") ?: file
            } ?: "直连"
        } else "直连"

        SmartRuleManager.triggerManualSwitch(resolvedFile, active)
    }

    private fun maybeRestartVpnForAppRule(newConfig: AppRuleConfig) {
        val vpnState = VpnStateRepository.vpnState.value
        if (!vpnState.isRunning) {
            return
        }
        val appliedVersion = vpnState.appRuleVersion
        val expectedVersion = if (newConfig.enabled) newConfig.version else null
        val versionChanged = appliedVersion != expectedVersion
        

        if (!versionChanged) return
        val tunnelFile = vpnState.tunnelFile ?: return
        val tunnelName = vpnState.tunnelName ?: "Unknown"

        runCatching {

            // Log split change before restart.
            logEvent(
                EventType.APP_RULE_CHANGED,
                tunnelName,
                tunnelName,
                descPrefix = "重启隧道"
            )

            // val stopIntent = Intent(appContext, SmartAgent::class.java).apply {
            //     action = SmartAgent.ACTION_STOP_TUNNEL
            // }
            // appContext.startService(stopIntent)

            val startIntent = Intent(appContext, SmartAgent::class.java).apply {
                action = SmartAgent.ACTION_START_TUNNEL
                putExtra(SmartAgent.EXTRA_TUNNEL_FILE, tunnelFile)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(startIntent)
            } else {
                appContext.startService(startIntent)
            }
        }.onFailure {
            android.util.Log.e("SmartConfigRepo", "maybeRestart failed", it)
            it.printStackTrace()
        }
    }

    fun logEvent(
        type: EventType,
        from: String,
        to: String,
        ssid: String? = null,
        error: String? = null,
        descPrefix: String? = null
    ) {
        val map = mutableMapOf(
            "type" to type.label,
            "from" to from,
            "to" to to
        )
        if (ssid != null) map["ssid"] = ssid
        if (error != null) map["error"] = error
        if (descPrefix != null) map["descPrefix"] = descPrefix
        val msg = json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.JsonObject(map.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }))
        db.insertLog(System.currentTimeMillis(), msg)
    }

    fun getLogs(limit: Int = 10, offset: Int = 0): List<Map<String, Any>> {
        return db.getLogs(limit, offset).map { (ts, msg) ->
            mapOf("timestamp" to ts, "message" to msg)
        }
    }

    fun clearLogs() {
        db.clearLogs()
    }

    enum class EventType(val label: String) {
        MANUAL("手动切换"),
        APP_START("SmartAgent启动"),

        // Log Labels for Network State
        WIFI_CONNECTED("Wifi已连接"),
        WIFI_DISCONNECTED("Wifi断开"),
        MOBILE_CONNECTED("移动数据已连接"),
        MOBILE_DISCONNECTED("移动数据已断开"),

        // Event Sources
        NETWORK_AVAILABLE("网络可用"),
        NETWORK_LOST("网络断开"),

        RULE_UPDATED("规则更新"),
        APP_RULE_CHANGED("分流变更"),
        TUNNEL_ERROR("连接失败"),
        FALLBACK("连接回退")
    }
}
