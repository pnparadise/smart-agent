package com.smart

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.RouteInfo
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.smart.component.SmartToast
import kotlinx.coroutines.*
import java.net.Inet4Address
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

    // --- 优化策略 1: 规则需求快照 ---
    // 用于标记当前生效的规则集是否需要特定的硬件信息。
    // 如果为 false，相关的数据获取逻辑（如 IPC 调用、遍历）将被跳过，极大地节省资源。
    private data class RuleRequirements(
        val needsSsid: Boolean = false,    // 是否配置了 SSID 规则
        val needsGateway: Boolean = false, // 是否配置了 网关 规则
        val needsIpv6: Boolean = false     // 是否配置了 IPv6 规则
    )

    @Volatile
    private var activeRequirements = RuleRequirements()

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
        val envFingerprint: String
            get() = "${network?.type?.name ?: "NONE"}_${network?.ssid ?: ""}_${network?.gatewayIp ?: ""}"
    }

    // 单一状态源，所有修改必须同步
    private var state = RuntimeState()

    // --- 网络模型定义 ---

    enum class NetworkType { WIFI, MOBILE, NONE }

    data class NetworkState(
        val type: NetworkType,
        val ssid: String? = null,
        val hasIpv6: Boolean = false,
        val gatewayIp: String? = null,

        // --- 优化策略 2: 能力指纹 ---
        // 用于在回调入口处快速比对，过滤掉信号强度、链路带宽波动等无意义回调
        val capsFingerprint: String = ""
    ) {
        override fun toString(): String {
            return "[Type: $type, SSID: ${ssid ?: "NULL"}, IPv6: $hasIpv6, Gateway: ${gatewayIp ?: "NULL"}]"
        }
    }

    // --- 初始化与配置 ---

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
            this.context = context.applicationContext
            SmartConfigRepository.init(context)
            logDebug("SmartRuleManager init completed", level = "INFO")
        }

        scope.launch {
            SmartConfigRepository.agentRuleConfig.collect { config ->
                onAgentRuleUpdated(null, config)
            }
        }
    }

    // --- 网络回调处理 (核心优化区) ---

    private fun handleCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
        val isInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        // 过滤 VPN 网络，只关注物理底层网络
        if (!isInternet) return
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return

        val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        // [降噪逻辑] 计算轻量级指纹
        // Android 系统会因为链路带宽(LinkDownstreamBandwidthKbps)微小变化频繁回调此方法。
        // 我们只关心：1. 联网能力 2. 验证状态 3. TransportInfo 对象引用(Android Q+ SSID 变化会导致此对象哈希变化)
        val transportInfoHash = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            caps.transportInfo?.hashCode() ?: 0
        } else 0

        val currentFingerprint = "$isValidated|$transportInfoHash"

        val extractedState = synchronized(availableNetworks) {
            val oldState = availableNetworks[network]

            // 如果已有记录，且核心指纹没变，说明是信号强弱等噪音，直接 return，不执行耗时解析
            if (oldState != null && oldState.capsFingerprint == currentFingerprint) {
                // 特例：如果之前需要 SSID 但没拿到 (ssid=null)，且现在指纹没变，
                // 我们依然不应该在这里重试，而是交给 recalculateGlobalState 的防抖逻辑去处理，避免死循环
                return
            }

            // 指纹变了，执行解析 (注意：extractNetworkStateFromHandle 内部也进行了按需优化)
            val newStateRaw = extractNetworkStateFromHandle(network, caps)
            val newState = newStateRaw.copy(capsFingerprint = currentFingerprint)

            // 状态合并逻辑：如果新状态 SSID 丢失但旧状态有，则继承旧 SSID (防止瞬时丢失)
            val finalState = if (oldState != null &&
                oldState.type == NetworkType.WIFI &&
                !oldState.ssid.isNullOrBlank() &&
                newState.type == NetworkType.WIFI &&
                newState.ssid.isNullOrBlank()) {

                logDebug("[MapUpdate] 忽略瞬时 SSID 丢失. 保持: ${oldState.ssid}", level = "WARN")
                newState.copy(ssid = oldState.ssid)
            } else {
                newState
            }

            availableNetworks[network] = finalState

            // 仅当业务属性发生实质变化时才打印日志
            if (oldState?.ssid != finalState.ssid || oldState?.type != finalState.type) {
                logDebug("[NetChange] NetID: $network 状态实质变更. 新SSID: ${finalState.ssid}")
            }

            finalState // 返回给外部以便可能的后续处理，虽然这里主要靠 Map 更新
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

    // 重新计算全局网络状态（防抖 + 延迟 Fallback）
    private fun recalculateGlobalState() {
        networkEvaluationJob?.cancel()
        networkEvaluationJob = scope.launch {
            // [排查点]：防抖 300ms
            // 这意味着如果网络在短时间内剧烈波动，我们只会在稳定后执行一次昂贵的检查
            delay(300)

            var bestState = resolveBestNetwork()
            val req = activeRequirements // 获取当前规则需求快照

            // [关键优化]：延迟且受限的 WifiManager Fallback
            // 只有同时满足以下条件才调用 WifiManager (IPC 耗电操作)：
            // 1. 当前判定为 WiFi 网络
            // 2. 常规方法未能获取到 SSID (bestState.ssid == null)
            // 3. 用户确实配置了需要 SSID 的规则 (req.needsSsid == true)
            if (bestState.type == NetworkType.WIFI && bestState.ssid == null && req.needsSsid) {
                logDebug("[LateCheck] 规则需要SSID但缺失，尝试强制补救读取...", level = "WARN")
                val rawFallback = getSsidFromWifiManagerFallback()
                val lateSsid = normalizeSsid(rawFallback)
                if (lateSsid != null) {
                    logDebug("[LateCheck] 补救成功！获取到 SSID: $lateSsid", level = "INFO")
                    bestState = bestState.copy(ssid = lateSsid)
                }
            }

            val event = if (bestState.type == NetworkType.NONE)
                SmartConfigRepository.EventType.NETWORK_LOST
            else SmartConfigRepository.EventType.NETWORK_AVAILABLE

            evaluateChange(event, bestState)
        }
    }

    private fun handleNetworkLost(network: Network) {
        logDebug("[Callback] Network Lost: $network")
        synchronized(availableNetworks) {
            if (availableNetworks.remove(network) != null) {
                // 只有真的移除了才触发重算
                recalculateGlobalState()
            }
        }
    }

    fun onAgentRuleUpdated(
        eventType: SmartConfigRepository.EventType?,
        config: AgentRuleConfig = SmartConfigRepository.agentRuleConfig.value
    ) {
        synchronized(this) {
            // --- 优化策略: 更新规则需求 ---
            if (config.enabled) {
                val needsSsid = config.rules.any { it.enabled && it.type == RuleType.WIFI_SSID }
                val needsGateway = config.rules.any { it.enabled && it.type == RuleType.WIFI_GATEWAY }
                val needsIpv6 = config.rules.any { it.enabled && it.type == RuleType.IPV6_AVAILABLE }

                activeRequirements = RuleRequirements(needsSsid, needsGateway, needsIpv6)
                logDebug("规则需求更新: $activeRequirements", level = "INFO")
            } else {
                activeRequirements = RuleRequirements() // 全部为 false
            }

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
                    logDebug("规则引擎已启动，等待网络回调建立状态快照...", level = "INFO")
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
                // 情况3: 关闭 -> 停止监听
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
            startTunnelService(tunnelFile)
        }
    }

    // --- 核心业务：错误处理与 Fallback ---

    fun onTunnelStartFailed(failedFile: String, errorMsg: String) {
        scope.launch {
            // 1. 原子检查与状态更新
            val shouldTriggerFallback = synchronized(this@SmartRuleManager) {
                // 关键校验：如果当前意图已经变了，说明用户可能手动切了网络或关了开关，旧的错误应忽略
                if (state.pendingTargetFile != failedFile) {
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

            // 2. 只有有效错误才记录日志并提示
            val tunnelName = failedFile.substringBeforeLast(".")
            logDebug("隧道启动失败: $failedFile. 原因: $errorMsg", level = "ERROR")

            SmartConfigRepository.logEvent(
                SmartConfigRepository.EventType.TUNNEL_ERROR,
                tunnelName, "直连", error = errorMsg, descPrefix = "启动失败"
            )

            withContext(Dispatchers.Main) {
                SmartToast.showText(context, "启动隧道失败: $errorMsg")
            }

            // 3. 触发重新评估 (Fallback)
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
        object : ConnectivityManager.NetworkCallback() {
            // 注意：API S+ 建议添加 FLAG_INCLUDE_LOCATION_INFO，但逻辑通用
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                handleCapabilitiesChanged(network, caps)

            override fun onLost(network: Network) = handleNetworkLost(network)
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

            // 逻辑状态对比：如果规则不关心 IPv6，则忽略 IPv6 的差异
            if (isSameLogicalState(oldState.network, currentState)) {
                return
            }

            val oldFingerprint = oldState.envFingerprint

            // 更新网络
            var newState = oldState.copy(network = currentState)
            val newFingerprint = newState.envFingerprint

            // 只有当网络真正连上，且环境指纹发生变化时，才重置黑名单
            if (currentState.type != NetworkType.NONE && oldFingerprint != newFingerprint) {
                logDebug("环境变更 ($oldFingerprint -> $newFingerprint)，重置失败记录", level = "INFO")
                newState = newState.copy(ignoredTunnels = emptySet())
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

        val snapshot = synchronized(this) { state }
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
                RuleType.WIFI_SSID -> {
                    // 如果因为按需加载导致 ssid 为 null，这里自然匹配失败，符合预期
                    val match = (netState.type == NetworkType.WIFI) && netState.ssid == rule.value
                    if (match) logDebug("匹配成功: SSID=${rule.value}")
                    match
                }
                RuleType.WIFI_GATEWAY -> {
                    if (netState.type != NetworkType.WIFI) return@filter false
                    val currentGateway = netState.gatewayIp
                    if (currentGateway.isNullOrBlank()) return@filter false
                    val match = currentGateway == rule.value
                    if (match) logDebug("匹配成功: Gateway=${rule.value}")
                    match
                }
                RuleType.IPV6_AVAILABLE -> netState.hasIpv6
                RuleType.IPV4_AVAILABLE -> true
            }
        }

        // 3. 寻找有效规则 (Fallback 核心：过滤掉在快照黑名单中的文件)
        val validRule = matchedRules.firstOrNull { rule ->
            val file = rule.tunnelFile
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

        val logSsidOrGateway = when {
            validRule?.type == RuleType.WIFI_GATEWAY -> netState.gatewayIp
            else -> netState.ssid
        }

        SmartConfigRepository.logEvent(
            finalLogType, currentTunnel, targetTunnelName, logSsidOrGateway, descPrefix = actionDesc
        )
    }

    // --- Intent Helpers ---

    private fun startTunnelService(tunnelFile: String) {
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
        if (old.gatewayIp != new.gatewayIp) return false
        // 优化：只有在规则确实需要 IPv6 时，才认为 IPv6 的抖动是实质性变化
        if (activeRequirements.needsIpv6 && old.hasIpv6 != new.hasIpv6) return false
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
                logDebug("Network monitoring registered", level = "INFO")
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
     * 从 Network 句柄提取网络状态 (按需调用)
     */
    private fun extractNetworkStateFromHandle(network: Network, caps: NetworkCapabilities): NetworkState {
        // 获取当前规则需求快照
        val req = activeRequirements

        val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isMobile = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

        val type = when {
            isWifi -> NetworkType.WIFI
            isMobile -> NetworkType.MOBILE
            else -> NetworkType.NONE
        }

        if (type == NetworkType.NONE) return NetworkState(NetworkType.NONE)

        var ssid: String? = null

        // --- 优化策略: 按需获取 SSID ---
        // 只有是 WiFi 且规则需要 SSID 时才尝试获取
        if (isWifi && req.needsSsid) {
            var rawSsid: String? = null

            // 1. 优先尝试轻量级 TransportInfo (无 IPC 阻塞)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val info = caps.transportInfo
                if (info is WifiInfo) {
                    rawSsid = info.ssid
                }
            }

            ssid = normalizeSsid(rawSsid)
            // 注意：此处不进行 WifiManager Fallback，将其移至 recalculateGlobalState 以减少调用
        }

        // --- 优化策略: 按需遍历 LinkProperties ---
        var hasIpv6 = false
        var gatewayIp: String? = null

        if (req.needsIpv6 || req.needsGateway) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val linkProps = cm.getLinkProperties(network)

            if (req.needsIpv6) {
                hasIpv6 = linkProps?.linkAddresses?.any {
                    it.address is Inet6Address && !it.address.isLinkLocalAddress
                } == true
            }

            if (req.needsGateway && isWifi) {
                gatewayIp = linkProps?.let { resolveGatewayIp(it) }
            }
        }

        return NetworkState(type, ssid, hasIpv6, gatewayIp)
    }

    private fun getCurrentNetworkState(): NetworkState {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork ?: return NetworkState(NetworkType.NONE)
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return NetworkState(NetworkType.NONE)
        val state = extractNetworkStateFromHandle(activeNetwork, caps)
        return state
    }

    fun getCurrentGatewayIp(): String? {
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetwork ?: return null
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
            val linkProps = cm.getLinkProperties(activeNetwork) ?: return null
            resolveGatewayIp(linkProps)
        }.getOrNull()
    }

    private fun getSsidFromWifiManagerFallback(): String? {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            // 这是一个重量级调用
            val info = wm.connectionInfo ?: return null
            info.ssid
        } catch (e: Exception) {
            logDebug("WifiManager Fallback Error: ${e.message}", level = "ERROR")
            null
        }
    }

    private fun resolveGatewayIp(linkProperties: LinkProperties): String? {
        for (route: RouteInfo in linkProperties.routes) {
            if (route.isDefaultRoute && route.hasGateway()) {
                val gateway = route.gateway
                if (gateway is Inet4Address) {
                    return gateway.hostAddress
                }
            }
        }
        return null
    }

    private fun logDebug(message: String, level: String = "DEBUG") {
        SmartConfigRepository.logDebug(message, TAG, level)
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