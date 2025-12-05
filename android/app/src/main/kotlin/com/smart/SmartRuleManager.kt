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
    private var monitorJob: Job? = null
    private var debounceJob: Job? = null
    private var pendingOverrideEvent: SmartConfigRepository.EventType? = null
    private var lastEnabled: Boolean? = null

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
        // Android 14 still requires location permission for Wi-Fi scans.
        return hasFineLocation() && isLocationEnabled()
    }

    private fun readConnectedSsid(caps: NetworkCapabilities?): String? {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val fromCaps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (caps?.transportInfo as? WifiInfo)?.ssid
        } else null
        val fromWm = runCatching { wm.connectionInfo?.ssid }.getOrNull()
        val candidates = listOfNotNull(fromCaps, fromWm)
        val finalSsid = candidates
            .map { it.removeSurrounding("\"") }
            .firstOrNull { it.isNotBlank() && it != "<unknown ssid>" }
        Log.d(TAG, "readConnectedSsid caps=$fromCaps, wm=$fromWm, final=$finalSsid")
        return finalSsid
    }

    private fun logSsidDebug(stage: String, isWifi: Boolean, ssid: String?) {
        // Lightweight debug log to trace SSID lookup states.
        val canRead = canReadWifiSsid()
        val msg = buildString {
            append("[$stage] wifi=").append(isWifi)
            append(", ssid=").append(ssid ?: "null")
            append(", canRead=").append(canRead)
            append(", nearby=").append(hasFineLocation())
            append(", fine=").append(hasFineLocation())
            append(", locationEnabled=").append(isLocationEnabled())
        }
        Log.d(TAG, msg)
    }

    fun onConfigUpdated(eventType: SmartConfigRepository.EventType = SmartConfigRepository.EventType.RULE_UPDATED) {
        val config = SmartConfigRepository.agentRuleConfig.value
        if (!config.enabled) return
        triggerEvaluation(eventType)
    }

    fun init(context: Context) {
        this.context = context.applicationContext
        SmartConfigRepository.init(context)
        
        // Observe config changes
        scope.launch {
            SmartConfigRepository.agentRuleConfig.collect { config ->
                val previouslyEnabled = lastEnabled
                lastEnabled = config.enabled
                if (config.enabled) {
                    // Only transition actions when enabling.
                    val justEnabled = previouslyEnabled == false || previouslyEnabled == null
                    if (justEnabled) {
                        startMonitoring()
                        triggerEvaluation(SmartConfigRepository.EventType.APP_START)
                    }
                } else {
                    stopMonitoring()
                    // Reset state so next enable is treated as start
                    lastEvaluatedState = null
                    lastTriggerState = null
                }
            }
        }
    }

    private data class NetworkState(
        val isConnected: Boolean,
        val isWifi: Boolean,
        val ssid: String?
    )

    private var lastTriggerState: NetworkState? = null
    private var lastEvaluatedState: NetworkState? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Reset trigger state so a fresh evaluation runs when network becomes available.
            lastTriggerState = null
            triggerEvaluation()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isWifi = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            var ssid: String? = null
            if (isWifi && canReadWifiSsid()) {
                ssid = readConnectedSsid(networkCapabilities)
            }
            logSsidDebug("capabilitiesChanged", isWifi, ssid)
            
            val newState = NetworkState(hasInternet, isWifi, ssid)
            val prevState = lastTriggerState
            lastTriggerState = newState
            val wasDisconnected = prevState?.isConnected == false || prevState == null
            if (wasDisconnected && hasInternet) {
                triggerEvaluation()
            }
        }

        override fun onLost(network: Network) {
            lastTriggerState = null
            triggerEvaluation()
        }
    }

    private fun startMonitoring() {
        if (monitorJob != null) return
        monitorJob = scope.launch {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
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
        } catch (e: Exception) {
            // Ignore if not registered
        }
    }

    private fun triggerEvaluation(overrideEventType: SmartConfigRepository.EventType? = null) {
        if (overrideEventType != null) {
            pendingOverrideEvent = overrideEventType
        }
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(1000) // Debounce
            val finalOverride = pendingOverrideEvent ?: overrideEventType
            pendingOverrideEvent = null
            evaluateRules(finalOverride)
        }
    }

    private fun evaluateRules(overrideEventType: SmartConfigRepository.EventType? = null) {
        val config = SmartConfigRepository.agentRuleConfig.value
        if (!config.enabled) return

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(activeNetwork)
        val linkProps = cm.getLinkProperties(activeNetwork)

        val isConnected = activeNetwork != null && caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isWifi = isConnected && caps!!.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        
        var ssid: String? = null
        if (isWifi && canReadWifiSsid()) {
            ssid = readConnectedSsid(caps)
        }
        logSsidDebug("evaluateRules", isWifi, ssid)

        val currentState = NetworkState(isConnected, isWifi, ssid)
        
        // Determine EventType
        val baseEventType = when {
            lastEvaluatedState == null -> SmartConfigRepository.EventType.APP_START
            !lastEvaluatedState!!.isConnected && isConnected -> {
                if (isWifi) SmartConfigRepository.EventType.WIFI_CONNECTED else SmartConfigRepository.EventType.MOBILE_CONNECTED
            }
            lastEvaluatedState!!.isConnected && !isConnected -> {
                if (lastEvaluatedState!!.isWifi) SmartConfigRepository.EventType.WIFI_DISCONNECTED else SmartConfigRepository.EventType.MOBILE_DISCONNECTED
            }
            lastEvaluatedState!!.isWifi && !isWifi && isConnected -> SmartConfigRepository.EventType.MOBILE_CONNECTED
            !lastEvaluatedState!!.isWifi && isWifi && isConnected -> SmartConfigRepository.EventType.WIFI_CONNECTED
            lastEvaluatedState!!.isWifi && isWifi && lastEvaluatedState!!.ssid != ssid -> SmartConfigRepository.EventType.WIFI_CONNECTED
            else -> null
        }
        
        lastEvaluatedState = currentState

        // If no network, stop VPN if running
        if (!isConnected) {
            val vpnState = VpnStateRepository.vpnState.value
            if (vpnState.isRunning) {
                val intent = android.content.Intent(context, SmartAgent::class.java).apply {
                    action = SmartAgent.ACTION_STOP_TUNNEL
                }
                context.startService(intent)
                val logType = overrideEventType ?: baseEventType ?: SmartConfigRepository.EventType.WIFI_DISCONNECTED
                SmartConfigRepository.logEvent(
                    logType,
                    vpnState.tunnelName ?: "Unknown",
                    "直连",
                    ssid
                )
            }
            return
        }

        val hasIpv6 = linkProps?.linkAddresses?.any { it.address is Inet6Address && !it.address.isLinkLocalAddress } == true
        val hasIpv4 = true

        var matchedRule: AgentRule? = null
        for (rule in config.rules) {
            if (!rule.enabled) continue
            val match = when (rule.type) {
                RuleType.WIFI_SSID -> isWifi && ssid == rule.value
                RuleType.IPV6_AVAILABLE -> hasIpv6
                RuleType.IPV4_AVAILABLE -> hasIpv4
            }
            if (match) {
                matchedRule = rule
                break
            }
        }

        val vpnState = VpnStateRepository.vpnState.value
        val currentTunnel = vpnState.tunnelName ?: "直连"
        val targetTunnelFile = matchedRule?.tunnelFile
        val targetTunnel = matchedRule?.tunnelName ?: "直连"

        val finalEventType = overrideEventType ?: baseEventType ?: SmartConfigRepository.EventType.NETWORK_CHANGED

        if (currentTunnel != targetTunnel) {
            if (targetTunnel == "直连") {
                if (vpnState.isRunning) {
                    val intent = android.content.Intent(context, SmartAgent::class.java).apply {
                        action = SmartAgent.ACTION_STOP_TUNNEL
                    }
                    context.startService(intent)
                }
            } else {
                // Check if we need to start/switch
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
        
        SmartConfigRepository.logEvent(
            finalEventType,
            currentTunnel,
            targetTunnel,
            ssid,
            descPrefix = if (currentTunnel == targetTunnel) "保持隧道" else null
        )
    }
}
