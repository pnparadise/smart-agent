package com.smart

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
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

    companion object {
        private var instance: SmartTileService? = null
        private var pendingState: VpnState? = null
        private var lastTunnelFile: String? = null

        fun pushState(state: VpnState) {
            pendingState = state
            instance?.updateTileState(state)
            instance?.requestListeningState()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        instance = this
        ensureInit()
        // Apply latest state immediately; service may restart.
        val initial = pendingState ?: VpnStateRepository.vpnState.value
        updateTileState(initial)
        scope = CoroutineScope(Dispatchers.Main).also { sc ->
            startJob = sc.launch {
                VpnStateRepository.vpnState.collectLatest { state ->
                    updateTileState(state)
                }
            }
        }
        pendingState = null
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
        val currentState = VpnStateRepository.vpnState.value
        val tunnels = SmartConfigRepository.getTunnels()
        val desiredActive = !currentState.isRunning
        val targetFile = if (desiredActive) {
            lastTunnelFile ?: currentState.tunnelFile ?: tunnels.firstOrNull()?.get("file")
        } else currentState.tunnelFile ?: lastTunnelFile

        SmartRuleManager.toggleManualTunnel(targetFile, desiredActive)
        if (desiredActive && !targetFile.isNullOrBlank()) {
            lastTunnelFile = targetFile
        }
        updateTileState(VpnStateRepository.vpnState.value)
        requestListeningState()
    }

    private fun ensureInit() {
        SmartRuleManager.init(applicationContext)
    }

    private fun updateTileState(state: VpnState?) {
        val tile = qsTile ?: return
        val currentState = state ?: VpnStateRepository.vpnState.value
        if (!currentState.tunnelFile.isNullOrBlank()) {
            lastTunnelFile = currentState.tunnelFile
        }
        val active = currentState.isRunning
        val label = if (active) currentState.tunnelName ?: "易连" else "易连"
        val subtitle = label
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        applyTileText(tile, label, subtitle)
        tile.updateTile()
    }

    private fun requestListeningState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            TileService.requestListeningState(
                applicationContext,
                android.content.ComponentName(applicationContext, SmartTileService::class.java)
            )
        }
    }

    private fun applyTileText(tile: Tile, label: String?, subtitle: String?) {
        tile.label = label
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = subtitle
        }
    }
}
