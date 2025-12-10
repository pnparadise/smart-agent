package com.smart

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Statistics
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.BadConfigException
import com.wireguard.config.Config
import com.wireguard.config.Interface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

class SmartAgent : VpnService() {

    private var activeTunnel: Tunnel? = null
    private lateinit var backend: GoBackend
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private var statsJob: Job? = null
    private var activeAppRuleVersion: Int? = null
    private val notificationId = 1001
    private val notificationChannelId = "smart_vpn_channel"
    private val notificationChannelName = "VPN 服务"

    companion object {
        const val ACTION_START_TUNNEL = "com.smart.action.START_TUNNEL"
        const val ACTION_STOP_TUNNEL = "com.smart.action.STOP_TUNNEL"
        const val EXTRA_TUNNEL_FILE = "extra_tunnel_file"
    }

    override fun onCreate() {
        super.onCreate()
        SmartConfigRepository.init(this)
        backend = GoBackend(this@SmartAgent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TUNNEL -> {
                val tunnelFile = intent.getStringExtra(EXTRA_TUNNEL_FILE)
                if (tunnelFile != null) {
                    startVpn(tunnelFile)
                }
            }
            ACTION_STOP_TUNNEL -> {
                stopVpn()
            }
            else -> {
                val tunnelFile = intent?.getStringExtra(EXTRA_TUNNEL_FILE)
                if (tunnelFile != null) {
                    startVpn(tunnelFile)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }

    private fun notifyState(state: VpnState) {
        VpnStateRepository.updateState(state)
        SmartTileService.pushState(state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            TileService.requestListeningState(
                this,
                ComponentName(this, SmartTileService::class.java)
            )
        }
    }

    private fun createNotification(contentText: String): Notification {
        ensureNotificationChannel()
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, notificationChannelId)
            .setContentTitle("易连运行中")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_qs_tile)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                notificationChannelId,
                notificationChannelName,
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }

    private fun startVpn(fileName: String) {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            prepareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(prepareIntent)
            showToast("请授权 VPN 以启动隧道")
            return
        }

        val targetTunnelName = fileName.substringBeforeLast(".", fileName)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                notificationId,
                createNotification("正在连接 $targetTunnelName"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(notificationId, createNotification("正在连接 $targetTunnelName"))
        }

        coroutineScope.launch(Dispatchers.IO) {
            try {
                val tunnelContent = SmartConfigRepository.readTunnelConfig(fileName)
                    ?: throw IllegalArgumentException("未找到隧道配置")

                var config = Config.parse(ByteArrayInputStream(tunnelContent.toByteArray()))
                config = applyAppRuleConfig(config)
                val appRuleConfig = SmartConfigRepository.appRuleConfig.value
                activeAppRuleVersion = if (appRuleConfig.enabled) appRuleConfig.version else null

                notifyState(
                    VpnState(
                        isRunning = true,
                        tunnelName = targetTunnelName,
                        tunnelFile = fileName,
                        lastHandshake = null,
                        txBytes = 0L,
                        rxBytes = 0L,
                        appRuleVersion = activeAppRuleVersion
                    )
                )

                val oldTunnel = activeTunnel
                activeTunnel = null
                oldTunnel?.let { backend.setState(it, Tunnel.State.DOWN, null) }

                val newTunnel = object : Tunnel {
                    override fun getName(): String = targetTunnelName
                    override fun onStateChange(newState: Tunnel.State) {
                        val me = this
                        coroutineScope.launch(Dispatchers.Main) {
                            when (newState) {
                                Tunnel.State.UP -> pushStats(targetTunnelName, fileName)
                                Tunnel.State.DOWN -> {
                                    if (activeTunnel === me) {
                                        VpnStateRepository.updateState(
                                            VpnState(
                                                isRunning = false,
                                                tunnelName = null,
                                                tunnelFile = null,
                                                appRuleVersion = null
                                            )
                                        )
                                        activeTunnel = null
                                        activeAppRuleVersion = null
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                }
                backend.setState(newTunnel, Tunnel.State.UP, config)
                activeTunnel = newTunnel
                updateNotification("已连接 $targetTunnelName")
                startStatsPolling(targetTunnelName, fileName)

            } catch (e: Exception) {

                activeAppRuleVersion = null
                val errorMsg = extractErrorMessage(e)

                //只有当 activeTunnel 为 null 时，才说明没有其他隧道成功上位
                if (activeTunnel == null) {
                    stopVpn()
                }

                // 2. [逻辑决策] 汇报给 Manager
                // Manager 会判断当前状态是否发生过变更（竞态条件），决定是否 Fallback。
                SmartRuleManager.onTunnelStartFailed(fileName, errorMsg)
            }
        }
    }

    private fun stopVpn() {
        coroutineScope.launch {
            activeTunnel?.let {
                backend.setState(it, Tunnel.State.DOWN, null)
            }
            activeTunnel = null
            statsJob?.cancel()
            statsJob = null
            activeAppRuleVersion = null
            notifyState(
                VpnState(
                    isRunning = false,
                    tunnelName = null,
                    tunnelFile = null,
                    lastHandshake = null,
                    txBytes = 0L,
                    rxBytes = 0L,
                    appRuleVersion = null
                )
            )
            stopForeground(true)
        }
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, createNotification(contentText))
    }

    private fun pushStats(tunnelName: String, tunnelFile: String) {
        val stats = getStats()
        val peers = stats?.peers() ?: emptyArray()
        val peerStats = if (peers.isNotEmpty()) stats?.peer(peers.first()) else null

        val lastHandshakeText = peerStats?.latestHandshakeEpochMillis?.let { millis ->
            if (millis <= 0L) null else {
                val agoSeconds = (System.currentTimeMillis() - millis) / 1000
                "${agoSeconds}s 前"
            }
        }
        val tx = peerStats?.txBytes ?: 0L
        val rx = peerStats?.rxBytes ?: 0L

        notifyState(
            VpnState(
                isRunning = true,
                tunnelName = tunnelName,
                tunnelFile = tunnelFile,
                lastHandshake = lastHandshakeText,
                txBytes = tx,
                rxBytes = rx,
                appRuleVersion = activeAppRuleVersion
            )
        )
    }

    private fun getStats(): Statistics? =
        runCatching { backend.getStatistics(activeTunnel) }.getOrNull()

    private fun startStatsPolling(tunnelName: String, tunnelFile: String) {
        statsJob?.cancel()
        statsJob = coroutineScope.launch(Dispatchers.IO) {
            while (true) {
                if (activeTunnel == null) break
                if (VpnStateRepository.hasUiListener) {
                    pushStats(tunnelName, tunnelFile)
                }
                delay(1000)
            }
        }
    }

    private fun applyAppRuleConfig(config: Config): Config {
        val appRuleConfig = SmartConfigRepository.appRuleConfig.value
        if (!appRuleConfig.enabled || appRuleConfig.selectedApps.isEmpty()) return config

        val originalInterface = config.`interface`
        val ifaceBuilder = Interface.Builder()
            .setKeyPair(originalInterface.keyPair)
            .addAddresses(originalInterface.addresses)
            .addDnsServers(originalInterface.dnsServers)
            .addDnsSearchDomains(originalInterface.dnsSearchDomains)

        originalInterface.listenPort.ifPresent { ifaceBuilder.setListenPort(it) }
        originalInterface.mtu.ifPresent { ifaceBuilder.setMtu(it) }

        val selected = appRuleConfig.selectedApps.toSet()
        ifaceBuilder.includeApplications(selected)

        val newInterface = ifaceBuilder.build()
        val builder = Config.Builder().setInterface(newInterface)
        config.peers.forEach { builder.addPeer(it) }
        return builder.build()
    }

    private fun extractErrorMessage(throwable: Throwable): String {
        getBackendErrorChinese(throwable)?.let { return it }

        var realError: Throwable? = throwable
        while (realError != null) {
            if (realError is BadConfigException) break
            realError = realError.cause
        }
        val e = realError ?: throwable

        if (e is BadConfigException) {
            val sb = StringBuilder()
            sb.append("配置错误: ")
            val context = runCatching {
                val prettySection = e.section.getName()
                val prettyLocation = e.location.getName()
                "[$prettySection] $prettyLocation"
            }.getOrElse { "[${e.section.name}] ${e.location.name}" }
            sb.append(context)
            sb.append(" -> ")
            sb.append(e.reason.name)
            if (!e.text.isNullOrEmpty()) sb.append(": \"${e.text}\"")
            e.cause?.message?.let { sb.append("\n(原因: $it)") }
            return sb.toString()
        }

        val sb = StringBuilder()
        e.message?.let { sb.append(it) }
        e.cause?.let { cause ->
            if (sb.isNotEmpty()) sb.append(": ")
            val causeMsg = cause.message
            if (causeMsg != null && causeMsg != e.message) sb.append(causeMsg)
        }
        if (sb.isEmpty()) return "${e.javaClass.simpleName} (无错误描述)"
        return sb.toString()
    }

    private fun getBackendErrorChinese(t: Throwable): String? {
        var realError: Throwable? = t
        while (realError != null) {
            if (realError is BackendException) break
            realError = realError.cause
        }
        if (realError !is BackendException) return null

        val reason = realError.reason
        val mainMsg = when (reason) {
            BackendException.Reason.DNS_RESOLUTION_FAILURE -> "DNS 解析失败：无法找到服务器 IP"
            BackendException.Reason.TUN_CREATION_ERROR -> "无法创建 VPN 隧道：可能是权限不足或被其他 VPN 占用"
            BackendException.Reason.UNKNOWN_KERNEL_MODULE_NAME -> "内核模块错误：设备不支持"
            BackendException.Reason.VPN_NOT_AUTHORIZED -> "未授权 VPN 权限，或已被系统撤销授权"
            BackendException.Reason.UNABLE_TO_START_VPN -> "无法启动 VPN 服务，请重试或检查系统设置"
            BackendException.Reason.GO_ACTIVATION_ERROR_CODE -> "后端激活失败"
            BackendException.Reason.WG_QUICK_CONFIG_ERROR_CODE -> "配置文件格式错误"
            BackendException.Reason.TUNNEL_MISSING_CONFIG -> "隧道配置缺失"
            else -> "VPN 服务启动失败 (${reason.name})"
        }
        val causeMsg = realError.cause?.message
        return if (!causeMsg.isNullOrBlank()) "$mainMsg\n(详细原因: $causeMsg)" else mainMsg
    }

    private fun showToast(message: String) {
        coroutineScope.launch(Dispatchers.Main) {
            MessageBridge.send(message)
        }
    }
}