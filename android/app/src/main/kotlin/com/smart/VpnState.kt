package com.smart

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VpnState(
    val isRunning: Boolean = false,
    val tunnelName: String? = null,
    val tunnelFile: String? = null,
    val lastHandshake: String? = null,
    val txBytes: Long = 0L,
    val rxBytes: Long = 0L,
    val appRuleVersion: Int? = null
)

object VpnStateRepository {
    private val _vpnState = MutableStateFlow(VpnState())
    val vpnState = _vpnState.asStateFlow()
    @Volatile
    var hasUiListener: Boolean = false
    @Volatile
    var uiListenerCallback: ((Boolean) -> Unit)? = null

    fun updateState(newState: VpnState) {
        _vpnState.value = newState
    }

    fun setUiListening(listening: Boolean) {
        hasUiListener = listening
        uiListenerCallback?.invoke(listening)
    }
}
