package com.smart

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.net.Inet6Address

object SmartRuleManager {
    private const val TAG = "SmartRuleManager"
    private lateinit var context: Context
    private val scope = CoroutineScope(Dispatchers.Default)

    @Volatile
    private var initialized: Boolean = false

    // 协程 Jobs
    private var connectivityMonitorJob: Job? = null
    private var networkEvaluationJob: Job? = null
    private var rulesEvaluationJob: Job? = null

    // 记录当前所有可用的物理网络及其状态，用于仲裁最佳网络
    private val availableNetworks = mutableMapOf<Network, NetworkState>()

    // --- 核心状态管理 ---

    /**
     * 运行时状态快照 (不可变)
     * 所有的决策逻辑都基于这个对象的瞬间状态
     */
    private data class RuntimeState(
        // 配置开关
        val configEnabled: Boolean = false,

        // 当前物理网络状态
        val network: NetworkState? = null,

        // 当前“意图”连接的目标文件 (令牌)。
        // 用于解决竞态条件：只有当报错的文件等于这个令牌时，才触发 Fallback。
        val pendingTargetFile: String? = null,

        // 当前环境下的黑名单 (启动失败的隧道)
        val ignoredTunnels: Set<String> = emptySet()
    ) {
        // 环境指纹：用于判断是否切换了网络环境
        // 只有当 Type 或 SSID 改变时，才视为环境改变，从而清空黑名单
        val envFingerprint: String
            get() = "${network?.type?.name ?: "NONE"}_${network?.ssid ?: ""}"
    }

    // 单一状态源，所有修改必须同步
    private var state = RuntimeState()

    // --- 网络模型定义 ---

    enum class NetworkType { WIFI, MOBILE, NONE }

    data class NetworkState(
        val type: NetworkType,
        val ssid: String? = null,
        val hasIpv6: Boolean = false
    )

    // --- 初始化与配置 ---

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

    private fun handleCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return

        val newState = extractNetworkStateFromHandle(network, caps)
        synchronized(availableNetworks) {
            availableNetworks[network] = newState
        }
        recalculateGlobalState()
    }

    // 根据当前可用网络仲裁最佳物理网络（优先 Wi-Fi）
    private fun resolveBestNetwork(): NetworkState {
        synchronized(availableNetworks) {
            val wifi = availableNetworks.values.firstOrNull { it.type == NetworkType.WIFI }
            if (wifi != null) return wifi
            val mobile = availableNetworks.values.firstOrNull { it.type == NetworkType.MOBILE }
            if (mobile != null) return mobile
            return NetworkState(NetworkType.NONE)
        }
    }

    // 重新计算全局网络状态（防抖）
    private fun recalculateGlobalState() {
        networkEvaluationJob?.cancel()
        networkEvaluationJob = scope.launch {
            delay(300)
            val bestState = resolveBestNetwork()
            val event = if (bestState.type == NetworkType.NONE)
                SmartConfigRepository.EventType.NETWORK_LOST
            else SmartConfigRepository.EventType.NETWORK_AVAILABLE
            evaluateChange(event, bestState)
        }
    }

    private fun handleNetworkLost(network: Network) {
        synchronized(availableNetworks) {
            availableNetworks.remove(network)
        }
        recalculateGlobalState()
    }

    fun onAgentRuleUpdated(
        eventType: SmartConfigRepository.EventType?,
        config: AgentRuleConfig = SmartConfigRepository.agentRuleConfig.value
    ) {
        synchronized(this) {
            val oldEnabled = state.configEnabled

            if (config.enabled) {
                // 情况1: 首次开启或 App 启动 -> 完全重置
                if (!oldEnabled || eventType == SmartConfigRepository.EventType.APP_START) {
                    startMonitoring()
                    state = state.copy(
                        configEnabled = true,
                        network = null,
                        ignoredTunnels = emptySet(),
                        pendingTargetFile = null
                    )
                    Log.i(TAG, "规则引擎已启动，等待网络回调建立状态快照...")
                } else {
                    // 情况2: 仅仅是更新了配置 -> 重置黑名单，保留网络状态
                    state = state.copy(
                        configEnabled = true,
                        ignoredTunnels = emptySet()
                    )
                    if (eventType != null) {
                        enqueueEvaluation(eventType)
                    }
                }
            } else {
                // 情况3: 关闭 -> 停止监听，保留 false 状态，清空其他
                stopMonitoring()
                state = RuntimeState(configEnabled = false)
            }
        }
    }

    fun onAppRuleUpdated(newConfig: AppRuleConfig) {
        val vpnState = VpnStateRepository.vpnState.value
        if (!vpnState.isRunning) return
        val expectedVersion = if (newConfig.enabled) newConfig.version else null

        if (vpnState.appRuleVersion != expectedVersion) {
            val tunnelFile = vpnState.tunnelFile ?: return
            val tunnelName = vpnState.tunnelName ?: "Unknown"

            SmartConfigRepository.logEvent(
                SmartConfigRepository.EventType.APP_RULE_CHANGED,
                tunnelName, tunnelName, descPrefix = "重启隧道"
            )

            // startTunnelService 会自动更新 pendingTargetFile
            startTunnelService(tunnelFile)
        }
    }

    // --- 核心业务：错误处理与 Fallback ---

    /**
     * 隧道启动失败回调
     */
    fun onTunnelStartFailed(failedFile: String, errorMsg: String) {
        scope.launch {
            // 1. 原子检查与状态更新
            // 返回 true 表示这确实是当前任务的失败，需要处理；返回 false 表示是过期消息
            val shouldTriggerFallback = synchronized(this@SmartRuleManager) {
                // 关键校验：如果当前意图已经变了，说明用户可能手动切了网络或关了开关，旧的错误应忽略
                if (state.pendingTargetFile != failedFile) {
                    Log.d(TAG, "静默忽略过期错误: $failedFile, 当前意图: ${state.pendingTargetFile}")
                    return@synchronized false
                }

                // 记录黑名单，并清除意图（表示当前动作已终结）
                state = state.copy(
                    ignoredTunnels = state.ignoredTunnels + failedFile,
                    pendingTargetFile = null
                )
                return@synchronized true
            }

            if (!shouldTriggerFallback) return@launch

            // 2. 只有有效错误才记录日志和弹窗
            val tunnelName = failedFile.substringBeforeLast(".")
            Log.e(TAG, "隧道启动失败: $failedFile. 原因: $errorMsg")

            SmartConfigRepository.logEvent(
                SmartConfigRepository.EventType.TUNNEL_ERROR,
                tunnelName, "直连", error = errorMsg, descPrefix = "启动失败"
            )

            withContext(Dispatchers.Main) {
                MessageBridge.send("启动隧道失败: $errorMsg")
            }

            // 3. 触发重新评估 (Fallback)
            // 评估函数读取 state.ignoredTunnels 后，会自动跳过该文件
            enqueueEvaluation(SmartConfigRepository.EventType.TUNNEL_ERROR)
        }
    }

    /**
     * 手动切换
     */
    fun toggleManualTunnel(targetFile: String?, active: Boolean) {
        scope.launch {
            synchronized(this@SmartRuleManager) {
                // 手动行为具有最高优先级，重置之前的自动忽略记录
                state = state.copy(ignoredTunnels = emptySet())
            }

            val vpnState = VpnStateRepository.vpnState.value
            val tunnels = SmartConfigRepository.getTunnels()
            val resolvedFile = when {
                active && !targetFile.isNullOrBlank() -> targetFile
                active -> tunnels.firstOrNull()?.get("file")
                else -> null
            }

            val targetName = resolvedFile?.let { file ->
                tunnels.firstOrNull { it["file"] == file }?.get("name") ?: file.substringBeforeLast(".", file)
            } ?: "直连"
            val currentName = vpnState.tunnelName ?: "直连"
            val descPrefix = if (active && resolvedFile != null) "手动开启" else "手动断开"

            SmartConfigRepository.logEvent(
                SmartConfigRepository.EventType.MANUAL, currentName, targetName, descPrefix = descPrefix
            )

            if (active && resolvedFile != null) startTunnelService(resolvedFile)
            else stopTunnelService()
        }
    }

    // --- 网络监听逻辑 ---

    private val networkCallback: ConnectivityManager.NetworkCallback =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                init {
                    Log.d(TAG, "NetworkCallback initialized with FLAG_INCLUDE_LOCATION_INFO (S+)")
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                    handleCapabilitiesChanged(network, caps)

                override fun onLost(network: Network) = handleNetworkLost(network)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                init {
                    Log.d(TAG, "NetworkCallback initialized without location flag (pre-S)")
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                    handleCapabilitiesChanged(network, caps)

                override fun onLost(network: Network) = handleNetworkLost(network)
            }
        }

    // --- 核心评估逻辑 ---

    /**
     * 处理网络变更，更新 State，并决定是否清空黑名单
     */
    private suspend fun evaluateChange(
        eventType: SmartConfigRepository.EventType,
        explicitState: NetworkState? = null
    ) {
        val currentState = explicitState ?: getCurrentNetworkState()

        synchronized(this) {
            val oldState = state

            // 再次 Check 防止乱序
            if (isSameLogicalState(oldState.network, currentState)) return

            val oldFingerprint = oldState.envFingerprint

            // 更新网络
            var newState = oldState.copy(network = currentState)
            val newFingerprint = newState.envFingerprint

            // 策略：只有当网络真正连上，且指纹发生变化时，才重置黑名单
            // 如果只是断网 (NONE)，我们暂时保留黑名单，防止网络闪断导致重试循环
            if (currentState.type != NetworkType.NONE && oldFingerprint != newFingerprint) {
                Log.i(TAG, "环境变更 ($oldFingerprint -> $newFingerprint)，重置失败记录")
                newState = newState.copy(ignoredTunnels = emptySet())
            } else {
                if (currentState.type != NetworkType.NONE) {
                    Log.d(TAG, "环境波动但指纹未变 ($newFingerprint)，保留失败记录")
                }
            }

            state = newState
        }

        val finalEventType = if (currentState.type == NetworkType.NONE)
            SmartConfigRepository.EventType.NETWORK_LOST
        else eventType

        enqueueEvaluation(finalEventType, currentState)
    }

    private fun enqueueEvaluation(eventSource: SmartConfigRepository.EventType, preCalculatedState: NetworkState? = null) {
        rulesEvaluationJob?.cancel()
        rulesEvaluationJob = scope.launch {
            evaluateRulesInternal(eventSource, preCalculatedState)
        }
    }

    private fun evaluateRulesInternal(
        eventSource: SmartConfigRepository.EventType,
        preCalculatedState: NetworkState? = null
    ) {
        val config = SmartConfigRepository.agentRuleConfig.value
        if (!config.enabled) return

        // 【关键】获取当前状态快照，后续逻辑全部基于此快照，保证一致性
        val snapshot = synchronized(this) { state }

        // 优先使用传入的预计算状态，否则使用快照中的，最后兜底查询
        val netState = preCalculatedState ?: snapshot.network ?: getCurrentNetworkState()
        val isConnected = netState.type != NetworkType.NONE

        // 1. 处理完全断网
        if (!isConnected) {
            val vpnState = VpnStateRepository.vpnState.value
            if (vpnState.isRunning) {
                stopTunnelService()
                SmartConfigRepository.logEvent(
                    SmartConfigRepository.EventType.NETWORK_LOST,
                    vpnState.tunnelName ?: "Unknown", "直连", netState.ssid, descPrefix = "网络完全断开"
                )
            }
            return
        }

        // 2. 匹配规则
        val matchedRules = config.rules.filter { rule ->
            if (!rule.enabled) return@filter false
            when (rule.type) {
                RuleType.WIFI_SSID -> (netState.type == NetworkType.WIFI) && netState.ssid == rule.value
                RuleType.IPV6_AVAILABLE -> netState.hasIpv6
                RuleType.IPV4_AVAILABLE -> true
            }
        }

        // 3. 寻找有效规则 (Fallback 核心：过滤掉在快照黑名单中的文件)
        val validRule = matchedRules.firstOrNull { rule ->
            val file = rule.tunnelFile
            // 允许直连(file为空)通过；或者该文件不在忽略列表中
            file.isNullOrBlank() || !snapshot.ignoredTunnels.contains(file)
        }

        val targetTunnelFile = validRule?.tunnelFile
        val targetTunnelName = validRule?.tunnelName ?: "直连"

        // 4. 判断是否需要动作
        val vpnState = VpnStateRepository.vpnState.value
        val currentTunnel = vpnState.tunnelName ?: "直连"
        val appConfig = SmartConfigRepository.appRuleConfig.value
        val appRuleVersionMismatch = vpnState.isRunning && appConfig.enabled && vpnState.appRuleVersion != appConfig.version
        val isFallbackTriggered = eventSource == SmartConfigRepository.EventType.TUNNEL_ERROR

        val needSwitch = (currentTunnel != targetTunnelName) ||
                (isFallbackTriggered && targetTunnelName != "直连") ||
                (eventSource == SmartConfigRepository.EventType.APP_RULE_CHANGED && appRuleVersionMismatch) ||
                (targetTunnelFile == null && vpnState.tunnelFile != null) ||
                (targetTunnelFile != null && vpnState.tunnelFile != targetTunnelFile)

        if (!needSwitch) {
            // 如果触发了 Fallback 流程，但最终计算结果是直连，且当前已经是直连，记录日志
            if (isFallbackTriggered && targetTunnelName == "直连" && currentTunnel == "直连") {
                SmartConfigRepository.logEvent(
                    SmartConfigRepository.EventType.FALLBACK, "Unknown", "直连", netState.ssid, descPrefix = "回退直连"
                )
            }
            return
        }

        // 5. 执行切换
        var actionDesc = if (isFallbackTriggered) "尝试下一规则" else "切换隧道"
        if (targetTunnelName == "直连") {
            if (isFallbackTriggered) actionDesc = "回退直连"
            if (vpnState.isRunning) stopTunnelService()
        } else {
            targetTunnelFile?.let { startTunnelService(it) }
        }

        val finalLogType = when {
            isFallbackTriggered -> SmartConfigRepository.EventType.FALLBACK
            eventSource == SmartConfigRepository.EventType.NETWORK_AVAILABLE -> {
                if (netState.type == NetworkType.WIFI) SmartConfigRepository.EventType.WIFI_CONNECTED
                else SmartConfigRepository.EventType.MOBILE_CONNECTED
            }
            else -> eventSource
        }

        SmartConfigRepository.logEvent(
            finalLogType, currentTunnel, targetTunnelName, netState.ssid, descPrefix = actionDesc
        )
    }

    // --- Intent Helpers ---

    private fun startTunnelService(tunnelFile: String) {
        // 更新意图状态
        synchronized(this) {
            state = state.copy(pendingTargetFile = tunnelFile)
        }

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
        // 清除意图状态
        synchronized(this) {
            state = state.copy(pendingTargetFile = null)
        }

        val intent = Intent(context, SmartAgent::class.java).apply {
            action = SmartAgent.ACTION_STOP_TUNNEL
        }
        context.startService(intent)
    }

    // --- 辅助方法 ---

    private fun isSameLogicalState(old: NetworkState?, new: NetworkState?): Boolean {
        if (old == null || new == null) return old == new
        if (old.type != new.type) return false
        if (old.ssid != new.ssid) return false
        // LinkProperties 变动频繁，如果其他都一样，忽略 IPv6 的细微抖动
        if (old.hasIpv6 != new.hasIpv6) return false
        return true
    }

    private fun startMonitoring() {
        if (connectivityMonitorJob != null) return
        connectivityMonitorJob = scope.launch {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .removeTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()
            try {
                cm.registerNetworkCallback(request, networkCallback)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun stopMonitoring() {
        connectivityMonitorJob?.cancel()
        connectivityMonitorJob = null
        synchronized(availableNetworks) { availableNetworks.clear() }
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) { }
    }

    /**
     * 根据特定的 Network 句柄提取网络状态
     */
    private fun extractNetworkStateFromHandle(network: Network, caps: NetworkCapabilities): NetworkState {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isMobile = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

        val type = when {
            isWifi -> NetworkType.WIFI
            isMobile -> NetworkType.MOBILE
            else -> NetworkType.NONE
        }

        if (type == NetworkType.NONE) return NetworkState(NetworkType.NONE)

        var ssid: String? = null
        if (isWifi) {
            var rawSsid: String? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val info = caps.transportInfo
                if (info is WifiInfo) {
                    rawSsid = info.ssid
                }
                Log.d(TAG, "SSID from transportInfo (Q+): ${rawSsid ?: "null"}")
            } else {
                // 兼容旧版：TransportInfo 不包含 SSID，返回 null
                rawSsid = null
                Log.d(TAG, "SSID from transportInfo (pre-Q): ${rawSsid ?: "null"}")
            }

            // 优先使用 transportInfo 的值，并经过 normalize 过滤 "<unknown ssid>"
            val normalizedRaw = normalizeSsid(rawSsid)
            if (normalizedRaw == null) {
                // 尝试使用 WifiManager 作为兜底（在部分设备上 transportInfo 可能拿不到）
                ssid = normalizeSsid(getSsidFromWifiManagerFallback())
                Log.d(TAG, "SSID fallback via WifiManager: ${ssid ?: "null"}")
            } else {
                ssid = normalizedRaw
            }

            Log.d(TAG, "SSID resolved after normalize: ${ssid ?: "null"}")
        }

        // 提取 IPv6
        val linkProps = cm.getLinkProperties(network)
        val hasIpv6 = linkProps?.linkAddresses?.any {
            it.address is Inet6Address && !it.address.isLinkLocalAddress
        } == true

        return NetworkState(type, ssid, hasIpv6)
    }

    private fun getCurrentNetworkState(): NetworkState {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork ?: return NetworkState(NetworkType.NONE)
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return NetworkState(NetworkType.NONE)
        return extractNetworkStateFromHandle(activeNetwork, caps)
    }

    private fun getSsidFromWifiManagerFallback(): String? {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wm.connectionInfo ?: return null
            info.ssid
        } catch (e: Exception) {
            null
        }
    }

    private fun normalizeSsid(rawSsid: String?): String? {
        if (rawSsid.isNullOrBlank() || rawSsid == "<unknown ssid>") return null
        return if (rawSsid.startsWith("\"") && rawSsid.endsWith("\"")) {
            rawSsid.substring(1, rawSsid.length - 1)
        } else {
            rawSsid
        }
    }
}
