package com.smart

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import java.net.Inet6Address
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

object SmartRuleManager {
    private const val TAG = "SmartRuleManager"
    private lateinit var context: Context
    private val scope = CoroutineScope(Dispatchers.Default)
    @Volatile
    private var initialized: Boolean = false

    // 任务控制
    private var monitorJob: Job? = null
    private var debounceJob: Job? = null
    private var evalJob: Job? = null

    // 状态缓存
    private var lastEnabled: Boolean? = null

    // [省电优化] 上次评估过的网络状态指纹，用于对比是否需要重新评估
    @Volatile
    private var lastEvaluatedState: NetworkState? = null

    // [智能重试] 当前会话中需要忽略的隧道文件（尝试连接失败过的）
    // 使用 Set 存储，当网络变更或配置变更时清空，当连接报错时添加
    private val ignoredTunnelFiles = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    enum class NetworkType { WIFI, MOBILE, NONE }

    data class NetworkState(
        val type: NetworkType,
        val ssid: String? = null,
        val hasIpv6: Boolean = false
    )

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
            this.context = context.applicationContext
            SmartConfigRepository.init(context)
        }

        scope.launch {
            SmartConfigRepository.agentRuleConfig.collect { config ->
                onAgentRuleUpdated(null, config)
            }
        }
    }

    /**
     * Agent 规则配置变更触发
     */
    fun onAgentRuleUpdated(eventType: SmartConfigRepository.EventType?, config: AgentRuleConfig = SmartConfigRepository.agentRuleConfig.value) {
        val previouslyEnabled = lastEnabled
        lastEnabled = config.enabled

        if (config.enabled) {
            if (previouslyEnabled == false || previouslyEnabled == null || eventType == SmartConfigRepository.EventType.APP_START) {
                startMonitoring()
                resetIgnoredTunnels()
                lastEvaluatedState = null
                enqueueEvaluation(SmartConfigRepository.EventType.APP_START)
                return
            }
            // 配置变了，之前的失败记录可能不再适用，重置
            resetIgnoredTunnels()
            // 强制触发评估（配置变更属于逻辑变更，不依赖网络状态变化）
            if (eventType != null) {
                enqueueEvaluation(eventType)
            }
        } else {
            stopMonitoring()
            resetIgnoredTunnels()
            lastEvaluatedState = null
        }
    }

    /**
     * 应用分流配置变更回调
     */
    fun onAppRuleUpdated(newConfig: AppRuleConfig) {
        val vpnState = VpnStateRepository.vpnState.value
        if (!vpnState.isRunning) return
        val expectedVersion = if (newConfig.enabled) newConfig.version else null
        val appliedVersion = vpnState.appRuleVersion
        val versionChanged = appliedVersion != expectedVersion
        if (!versionChanged) return

        val tunnelFile = vpnState.tunnelFile ?: return
        val tunnelName = vpnState.tunnelName ?: "Unknown"

        SmartConfigRepository.logEvent(
            SmartConfigRepository.EventType.APP_RULE_CHANGED,
            tunnelName,
            tunnelName,
            descPrefix = "重启隧道"
        )

        startTunnelService(tunnelFile)
    }

    /**
     * 隧道连接失败触发 (Fallback 入口)
     */
    fun onTunnelConnectionFailed(failedTunnelFile: String?) {
        if (!failedTunnelFile.isNullOrBlank()) {
            ignoredTunnelFiles.add(failedTunnelFile)
            Log.d(TAG, "Tunnel failed: $failedTunnelFile, adding to ignore list and re-evaluating.")
        }
        // 触发评估，此时 EventType 为 TUNNEL_ERROR
        enqueueEvaluation(SmartConfigRepository.EventType.TUNNEL_ERROR)
    }

    /**
     * 手动切换
     */
    fun toggleManualTunnel(targetFile: String?, active: Boolean) {
        scope.launch {
            // 手动干预属于强制行为，清空之前的自动规则忽略列表
            resetIgnoredTunnels()

            val vpnState = VpnStateRepository.vpnState.value
            val fromTunnel = vpnState.tunnelName ?: "直连"

            val tunnels = SmartConfigRepository.getTunnels()
            val resolvedFile = when {
                active && !targetFile.isNullOrBlank() -> targetFile
                active -> tunnels.firstOrNull()?.get("file")
                else -> null
            }

            val toTunnel = if (active) {
                resolvedFile?.let { file ->
                    tunnels.firstOrNull { it["file"] == file }?.get("name") ?: file
                } ?: "直连"
            } else "直连"

            SmartConfigRepository.logEvent(
                SmartConfigRepository.EventType.MANUAL,
                fromTunnel,
                toTunnel,
                descPrefix = "手动切换"
            )

            if (active && resolvedFile != null) {
                startTunnelService(resolvedFile)
            } else {
                stopTunnelService()
            }
        }
    }

    // --- Connectivity Monitoring ---

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            // 1. 基础过滤：必须有 INTERNET 能力
            if (!networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return

            // 获取当前这一刻的新状态
            val newState = getNetworkStateFromNetwork(network, networkCapabilities)

            //如果是 Wi-Fi 且没有 SSID，直接视为“无效/中间”状态，丢弃不处理  Android 会在几百毫秒后发送带有 SSID 的第二次回调
            if (newState.type == NetworkType.WIFI && newState.ssid.isNullOrBlank()) {
                return
            }

            synchronized(this@SmartRuleManager) {
                // 2. [省电核心] 深度对比：如果新状态和上一次评估过的状态“逻辑上”是一样的，直接忽略
                // 比如：信号强度变了、LinkSpeed 变了，但 NetworkType 没变、SSID 没变 -> return
                if (isSameLogicalState(lastEvaluatedState, newState)) {
                    return
                }
            }

            // 3. 状态变了，防抖后评估
            debounceJob?.cancel()
            debounceJob = scope.launch {
                delay(500) // 等待网络状态稳定

                // 再次获取最新状态（防抖期间可能又变了，或者断了）
                val finalState = getCurrentNetworkState()

                synchronized(this@SmartRuleManager) {
                    // 二次检查：可能在 delay 期间状态又变回去了
                    if (isSameLogicalState(lastEvaluatedState, finalState)) return@launch
                    // 更新快照
                    lastEvaluatedState = finalState
                }

                // 网络环境变了，清空忽略列表，给所有规则重新尝试的机会
                resetIgnoredTunnels()
                enqueueEvaluation(SmartConfigRepository.EventType.NETWORK_AVAILABLE)
            }
        }

        override fun onLost(network: Network) {
            debounceJob?.cancel()
            debounceJob = scope.launch {
                delay(500)
                val activeState = getCurrentNetworkState()

                synchronized(this@SmartRuleManager) {
                    if (isSameLogicalState(lastEvaluatedState, activeState)) return@launch
                    lastEvaluatedState = activeState
                }

                // 无论变成什么，只要断网或切换，都重置忽略列表
                resetIgnoredTunnels()

                if (activeState.type != NetworkType.NONE) {
                    // 只是切换（如 Wifi -> 流量）
                    enqueueEvaluation(SmartConfigRepository.EventType.NETWORK_AVAILABLE)
                } else {
                    // 真断网
                    enqueueEvaluation(SmartConfigRepository.EventType.NETWORK_LOST)
                }
            }
        }
    }

    // --- Core Evaluation Logic ---

    /**
     * 核心评估入口
     * 每次调用都会取消上一次正在进行的 evalJob
     * 保证：如果有新的 trigger (如网络切换)，旧的 fallback 或评估过程会被直接丢弃
     */
    private fun enqueueEvaluation(eventSource: SmartConfigRepository.EventType) {
        evalJob?.cancel()
        evalJob = scope.launch {
            evaluateRulesInternal(eventSource)
        }
    }

    private fun evaluateRulesInternal(eventSource: SmartConfigRepository.EventType) {
        val config = SmartConfigRepository.agentRuleConfig.value
        if (!config.enabled) return

        val state = getCurrentNetworkState()
        val isConnected = state.type != NetworkType.NONE

        // 1. 处理完全断网
        if (!isConnected) {
            val vpnState = VpnStateRepository.vpnState.value
            if (vpnState.isRunning) {
                stopTunnelService()

                // 尝试推断断开的是什么网络，用于日志
                val logType = SmartConfigRepository.EventType.WIFI_DISCONNECTED // 默认
                // 实际日志记录
                SmartConfigRepository.logEvent(
                    logType,
                    vpnState.tunnelName ?: "Unknown",
                    "直连",
                    state.ssid,
                    descPrefix = "网络完全断开"
                )
            }
            return
        }

        // 2. 匹配规则
        val matchedRules = config.rules.filter { rule ->
            if (!rule.enabled) return@filter false
            when (rule.type) {
                RuleType.WIFI_SSID -> (state.type == NetworkType.WIFI) && state.ssid == rule.value
                RuleType.IPV6_AVAILABLE -> state.hasIpv6
                RuleType.IPV4_AVAILABLE -> true
            }
        }

        // 3. 选择目标规则 (Fallback 逻辑)
        // 从匹配的规则中，找到第一个 "其对应的 tunnelFile 不在 ignoredTunnelFiles 中" 的规则
        val validRule = matchedRules.firstOrNull { rule ->
            val file = rule.tunnelFile
            // 如果这个文件之前失败过，就跳过它
            !file.isNullOrBlank() && !ignoredTunnelFiles.contains(file)
        }

        // 如果 validRule 为 null，说明所有匹配的规则都失败过一次了，或者根本没匹配上
        // 此时目标就是 "直连" (Abandon Fallback)
        val targetTunnelFile = validRule?.tunnelFile
        val targetTunnelName = validRule?.tunnelName ?: "直连"

        // 4. 执行状态判断
        val vpnState = VpnStateRepository.vpnState.value
        val currentTunnel = vpnState.tunnelName ?: "直连"

        val appConfig = SmartConfigRepository.appRuleConfig.value
        val appRuleVersionMismatch = vpnState.isRunning && appConfig.enabled && vpnState.appRuleVersion != appConfig.version

        val isFallbackTriggered = eventSource == SmartConfigRepository.EventType.TUNNEL_ERROR

        // 判断是否需要动作
        val needSwitch = (currentTunnel != targetTunnelName) ||
                (isFallbackTriggered && targetTunnelName != "直连") || // 错误重试
                (eventSource == SmartConfigRepository.EventType.APP_RULE_CHANGED && appRuleVersionMismatch)

        // 5. 动作与日志
        var actionDesc = "保持连接"

        if (needSwitch) {
            actionDesc = if (isFallbackTriggered) "连接失败，尝试下一规则" else "切换隧道"

            if (targetTunnelName == "直连") {
                if (isFallbackTriggered) actionDesc = "所有规则均失败，回退直连"
                if (vpnState.isRunning) stopTunnelService()
            } else {
                targetTunnelFile?.let { file ->
                    val shouldRestart = !vpnState.isRunning || vpnState.tunnelFile != file || isFallbackTriggered || appRuleVersionMismatch
                    if (shouldRestart) {
                        startTunnelService(file)
                    }
                }
            }
        }

        // 6. 构造日志类型
        val finalLogType = when {
            isFallbackTriggered -> SmartConfigRepository.EventType.FALLBACK
            eventSource == SmartConfigRepository.EventType.NETWORK_AVAILABLE -> {
                if (state.type == NetworkType.WIFI) SmartConfigRepository.EventType.WIFI_CONNECTED
                else SmartConfigRepository.EventType.MOBILE_CONNECTED
            }
            else -> eventSource
        }

        SmartConfigRepository.logEvent(
            finalLogType,
            currentTunnel,
            targetTunnelName,
            state.ssid,
            descPrefix = actionDesc
        )
    }

    private fun resetIgnoredTunnels() {
        ignoredTunnelFiles.clear()
    }

    /**
     * 判断两个网络状态是否逻辑相等（用于去重，省电）
     * 只比较 Type, SSID, IPv6
     */
    private fun isSameLogicalState(old: NetworkState?, new: NetworkState): Boolean {
        if (old == null) return false
        if (old.type != new.type) return false
        if (old.ssid != new.ssid) return false
        if (old.hasIpv6 != new.hasIpv6) return false
        return true
    }

    private fun startMonitoring() {
        if (monitorJob != null) return
        monitorJob = scope.launch {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .removeTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            try {
                cm.registerNetworkCallback(request, networkCallback)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) { }
    }

    // --- Helper: Network State ---

    private fun getCurrentNetworkState(): NetworkState {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork ?: return NetworkState(NetworkType.NONE)
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return NetworkState(NetworkType.NONE)

        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return NetworkState(NetworkType.NONE)
        }

        return getNetworkStateFromNetwork(activeNetwork, caps)
    }

    private fun getNetworkStateFromNetwork(network: Network, caps: NetworkCapabilities): NetworkState {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val linkProps = cm.getLinkProperties(network)

        val hasIpv6 = linkProps?.linkAddresses?.any {
            it.address is Inet6Address && !it.address.isLinkLocalAddress
        } == true

        val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isMobile = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

        var ssid: String? = null
        if (isWifi) {
            ssid = readConnectedSsid(caps)
        }

        val type = when {
            isWifi -> NetworkType.WIFI
            isMobile -> NetworkType.MOBILE
            else -> NetworkType.NONE
        }

        return NetworkState(type, ssid, hasIpv6)
    }

    // --- Helper: Robust SSID Reading (关键修复) ---

    private fun readConnectedSsid(caps: NetworkCapabilities?): String? {
        var ssid: String? = null

        // 1. 尝试从 TransportInfo 获取 (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val wifiInfo = caps?.transportInfo as? WifiInfo
            ssid = wifiInfo?.ssid
        }

        // 2. 兜底：如果上面获取失败，强制调用 WifiManager (即使已过时，但为了解决权限/隐私问题必须用)
        if (ssid.isNullOrBlank() || ssid == "<unknown ssid>") {
            try {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val info = wm.connectionInfo
                if (info != null) {
                    ssid = info.ssid
                }
            } catch (e: Exception) {
                // Ignore permissions errors
            }
        }

        // 3. 数据清洗
        if (ssid != null) {
            if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                ssid = ssid.substring(1, ssid.length - 1)
            }
            if (ssid == "<unknown ssid>" || ssid.isEmpty()) {
                ssid = null
            }
        }
        return ssid
    }

    private fun startTunnelService(tunnelFile: String) {
        val intent = Intent(context, SmartAgent::class.java).apply {
            action = SmartAgent.ACTION_START_TUNNEL
            putExtra(SmartAgent.EXTRA_TUNNEL_FILE, tunnelFile)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun stopTunnelService() {
        val intent = Intent(context, SmartAgent::class.java).apply {
            action = SmartAgent.ACTION_STOP_TUNNEL
        }
        context.startService(intent)
    }
}
