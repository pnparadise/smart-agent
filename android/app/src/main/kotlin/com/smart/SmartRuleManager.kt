package com.smart

import android.Manifest
import android.content.Context
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

object SmartRuleManager {
    private const val TAG = "SmartRuleManager"
    private lateinit var context: Context
    private val scope = CoroutineScope(Dispatchers.Default)

    // 任务控制
    private var monitorJob: Job? = null
    private var debounceJob: Job? = null

    // 外部传入的强制事件类型
    private var pendingOverrideEvent: SmartConfigRepository.EventType? = null
    private var lastEnabled: Boolean? = null

    // --- 唯一的核心状态：当前经过验证的网络句柄 ---
    @Volatile
    private var lastValidatedNetwork: Network? = null

    // ... (hasFineLocation, isLocationEnabled, canReadWifiSsid, readConnectedSsid 保持不变) ...
    private fun hasFineLocation(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }
    }

    private fun canReadWifiSsid(): Boolean {
        return hasFineLocation() && isLocationEnabled()
    }

    private fun readConnectedSsid(caps: NetworkCapabilities?): String? {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val fromCaps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (caps?.transportInfo as? WifiInfo)?.ssid
        } else null
        val fromWm = if (fromCaps == null) runCatching { wm.connectionInfo?.ssid }.getOrNull() else null
        val finalSsid = listOfNotNull(fromCaps, fromWm)
            .map { it.removeSurrounding("\"") }
            .firstOrNull { it.isNotBlank() && it != "<unknown ssid>" }
        return finalSsid
    }

    fun onConfigUpdated(eventType: SmartConfigRepository.EventType = SmartConfigRepository.EventType.RULE_UPDATED) {
        val config = SmartConfigRepository.agentRuleConfig.value
        if (!config.enabled) return
        triggerEvaluation(eventType)
    }

    fun init(context: Context) {
        this.context = context.applicationContext
        SmartConfigRepository.init(context)

        scope.launch {
            SmartConfigRepository.agentRuleConfig.collect { config ->
                val previouslyEnabled = lastEnabled
                lastEnabled = config.enabled
                if (config.enabled) {
                    val justEnabled = previouslyEnabled == false || previouslyEnabled == null
                    if (justEnabled) {
                        startMonitoring()
                        triggerEvaluation(SmartConfigRepository.EventType.APP_START)
                    }
                } else {
                    stopMonitoring()
                    synchronized(this@SmartRuleManager) {
                        lastValidatedNetwork = null
                    }
                }
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // onAvailable 只是网络可用，还没验证是否可上网，等待 Capabilities 变化
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            // 必须是有互联网能力的网络
            if (!networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return
            }

            // 注意：NET_CAPABILITY_VALIDATED 有时会延迟，如果只依赖它可能会在切换瞬间造成短暂“断网”
            // 这里我们放宽一点，只要有 Internet 能力就记录，但在 evaluateRules 里会做二次确认

            synchronized(this@SmartRuleManager) {
                // 如果网络句柄变了，或者是同一个网络但能力变了（例如获得了 VALIDATED）
                if (lastValidatedNetwork != network) {
                    Log.d(TAG, "Network Changed: $network")
                    lastValidatedNetwork = network
                    triggerEvaluation()
                }
            }
        }

        override fun onLost(network: Network) {
            synchronized(this@SmartRuleManager) {
                if (network == lastValidatedNetwork) {
                    Log.d(TAG, "Network Lost: $network")
                    lastValidatedNetwork = null
                    // 网络丢失后，立即触发评估。
                    // 评估逻辑中会主动检查是否有备用网络（如数据流量）接管。
                    triggerEvaluation()
                }
            }
        }
    }

    private fun startMonitoring() {
        if (monitorJob != null) return
        monitorJob = scope.launch {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR) // 确保监听数据网络
                .removeTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            // 初始化状态
            val activeNetwork = cm.activeNetwork
            if (activeNetwork != null) {
                synchronized(this@SmartRuleManager) {
                    lastValidatedNetwork = activeNetwork
                }
            }

            cm.registerNetworkCallback(request, networkCallback)
            triggerEvaluation()
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

    private fun triggerEvaluation(overrideEventType: SmartConfigRepository.EventType? = null) {
        if (overrideEventType != null) {
            pendingOverrideEvent = overrideEventType
        }
        debounceJob?.cancel()
        debounceJob = scope.launch {
            // 这里的延时很重要，Wifi断开到数据网络接管通常有几百毫秒到1秒的间隙
            delay(1000)
            val finalOverride = pendingOverrideEvent ?: overrideEventType
            pendingOverrideEvent = null
            evaluateRules(finalOverride)
        }
    }

    /**
     * 核心修复逻辑：
     * 在判断是否断网时，不只依赖回调记录的 lastValidatedNetwork，
     * 而是主动去 ConnectivityManager 查一下当前有没有 Active Network。
     */
    private fun evaluateRules(overrideEventType: SmartConfigRepository.EventType? = null) {
        val config = SmartConfigRepository.agentRuleConfig.value
        if (!config.enabled) return

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // 1. 获取网络快照
        var currentNetwork = lastValidatedNetwork
        var caps = if (currentNetwork != null) cm.getNetworkCapabilities(currentNetwork) else null

        // [关键修改]：如果回调记录的网络已失效（为空或无能力），尝试主动获取系统当前的默认网络
        // 场景：Wifi 断开 (onLost -> null)，但数据网络已经自动接管，但回调可能还没来得及走完或被防抖处理
        if (currentNetwork == null || caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            val activeNetwork = cm.activeNetwork
            if (activeNetwork != null) {
                val activeCaps = cm.getNetworkCapabilities(activeNetwork)
                if (activeCaps != null && activeCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    Log.d(TAG, "Evaluate: Fallback to active system network: $activeNetwork")
                    currentNetwork = activeNetwork
                    caps = activeCaps
                    // 更新缓存，避免下次还判断为空
                    synchronized(this@SmartRuleManager) {
                        lastValidatedNetwork = activeNetwork
                    }
                }
            }
        }

        val linkProps = if (currentNetwork != null) cm.getLinkProperties(currentNetwork) else null

        // 最终确认连接状态
        val isConnected = currentNetwork != null && caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isWifi = isConnected && caps!!.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)

        var ssid: String? = null
        if (isWifi && canReadWifiSsid()) {
            ssid = readConnectedSsid(caps)
        }

        // --- 1. 处理真正的断网情况 ---
        // 只有当 Wifi 和 数据网络 都不存在时，才视为断网
        if (!isConnected) {
            val vpnState = VpnStateRepository.vpnState.value
            if (vpnState.isRunning) {
                val intent = android.content.Intent(context, SmartAgent::class.java).apply {
                    action = SmartAgent.ACTION_STOP_TUNNEL
                }
                context.startService(intent)

                // 记录断网日志
                SmartConfigRepository.logEvent(
                    overrideEventType ?: SmartConfigRepository.EventType.WIFI_DISCONNECTED, // 或者定义一个 NO_NETWORK
                    vpnState.tunnelName ?: "Unknown",
                    "直连",
                    ssid,
                    descPrefix = "网络完全断开"
                )
            }
            return
        }

        // --- 2. 匹配规则 ---
        // 此时如果是数据网络，isWifi 为 false，ssid 为 null，RuleType.WIFI_SSID 将匹配失败
        // 但 RuleType.IPV4/IPV6 等规则依然可以匹配
        val hasIpv6 = linkProps?.linkAddresses?.any { it.address is Inet6Address && !it.address.isLinkLocalAddress } == true
        val hasIpv4 = true

        var matchedRule: AgentRule? = null
        for (rule in config.rules) {
            if (!rule.enabled) continue
            val match = when (rule.type) {
                RuleType.WIFI_SSID -> isWifi && ssid == rule.value // 只有 Wifi 下才能匹配 SSID
                RuleType.IPV6_AVAILABLE -> hasIpv6
                RuleType.IPV4_AVAILABLE -> hasIpv4
            }
            if (match) {
                matchedRule = rule
                break
            }
        }

        // --- 3. 执行隧道切换 ---
        val vpnState = VpnStateRepository.vpnState.value
        val currentTunnel = vpnState.tunnelName ?: "直连"
        val targetTunnelFile = matchedRule?.tunnelFile
        val targetTunnel = matchedRule?.tunnelName ?: "直连"

        if (currentTunnel != targetTunnel) {
            if (targetTunnel == "直连") {
                if (vpnState.isRunning) {
                    val intent = android.content.Intent(context, SmartAgent::class.java).apply {
                        action = SmartAgent.ACTION_STOP_TUNNEL
                    }
                    context.startService(intent)
                }
            } else {
                val targetFile = targetTunnelFile
                if (!vpnState.isRunning || vpnState.tunnelFile != targetFile) {
                    val intent = android.content.Intent(context, SmartAgent::class.java).apply {
                        action = SmartAgent.ACTION_START_TUNNEL
                        putExtra(SmartAgent.EXTRA_TUNNEL_FILE, targetFile)
                    }
                    context.startForegroundService(intent)
                }
            }
        }

        // --- 4. 日志记录 ---
        val inferredEventType = if (isWifi) SmartConfigRepository.EventType.WIFI_CONNECTED else SmartConfigRepository.EventType.MOBILE_CONNECTED
        val finalEventType = overrideEventType ?: inferredEventType

        SmartConfigRepository.logEvent(
            finalEventType,
            currentTunnel,
            targetTunnel,
            ssid,
            descPrefix = if (currentTunnel == targetTunnel) "保持隧道" else "网络切换"
        )
    }
}