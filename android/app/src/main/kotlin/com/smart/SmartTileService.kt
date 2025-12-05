package com.smart

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel


class SmartTileService : TileService() {

    private var scope: CoroutineScope? = null
    private var startJob: Job? = null
    private val prefs by lazy { getSharedPreferences("tile_data", Context.MODE_PRIVATE) }

    companion object {
        private var instance: SmartTileService? = null
        private var pendingState: VpnState? = null
        private const val KEY_LABEL = "tile_label"
        private const val KEY_SUBTITLE = "tile_subtitle"

        fun pushState(state: VpnState) {
            pendingState = state
            instance?.updateTileState(state)
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        instance = this
        ensureInit()
        // Apply last persisted label/subtitle immediately; service may restart.
        restoreTileText()
        scope = CoroutineScope(Dispatchers.Main).also { sc ->
            startJob = sc.launch {
                VpnStateRepository.vpnState.collectLatest { state ->
                    updateTileState(state)
                }
            }
        }
        pendingState?.let { updateTileState(it) }
    }

    override fun onStopListening() {
        startJob?.cancel()
        startJob = null
        scope?.cancel()
        scope = null
        instance = null
        super.onStopListening()
    }

    override fun onClick() {
        ensureInit()
        val enabled = SmartConfigRepository.agentRuleConfig.value.enabled
        if (enabled) {
            SmartConfigRepository.setEnabled(false)
            stopTunnel()
        } else {
            SmartConfigRepository.setEnabled(true)
        }
        updateTileState(VpnStateRepository.vpnState.value)
    }

    private fun ensureInit() {
        SmartRuleManager.init(applicationContext)
    }

    private fun stopTunnel() {
        val intent = Intent(applicationContext, SmartAgent::class.java).apply {
            action = SmartAgent.ACTION_STOP_TUNNEL
        }
        applicationContext.startService(intent)
    }

    fun updateTileState(state: VpnState) {
        val tile = qsTile ?: return
        val activeConfig = SmartConfigRepository.agentRuleConfig.value.enabled
        val active = activeConfig && state.isRunning
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        val label = if (active && !state.tunnelName.isNullOrBlank()) state.tunnelName else "云助手"
        val subtitle = when {
            !activeConfig || !state.isRunning -> "已关闭"
            active -> state.tunnelName ?: "已连接"
            else -> "待连接"
        }
        persistTileText(label, subtitle)
        applyTileText(tile, label, subtitle)
        tile.updateTile()
    }

    private fun persistTileText(label: String?, subtitle: String?) {
        prefs.edit().apply {
            putString(KEY_LABEL, label)
            putString(KEY_SUBTITLE, subtitle)
        }.apply()
    }

    private fun restoreTileText() {
        val tile = qsTile ?: return
        val savedLabel = prefs.getString(KEY_LABEL, "云助手")
        val savedSubtitle = prefs.getString(KEY_SUBTITLE, savedLabel)
        applyTileText(tile, savedLabel, savedSubtitle)
        tile.updateTile()
    }

    private fun applyTileText(tile: Tile, label: String?, subtitle: String?) {
        tile.label = label
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = subtitle
        }
    }
}
