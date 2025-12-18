package com.smart.bridge

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.smart.SmartAgentVpnService
import com.smart.SmartRuleManager
import com.smart.component.SmartToast

class VpnControlHandler(
    private val activity: Activity,
    private val appContext: Context
) {
    companion object {
        const val REQ_CODE_VPN_PERMISSION = 101
    }

    private var pendingTunnelFile: String? = null

    fun toggleTunnel(file: String, active: Boolean) {
        if (active) {
            startVpnIntent(file)
        } else {
            SmartRuleManager.toggleManualTunnel(file, false)
        }
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQ_CODE_VPN_PERMISSION) return false

        val file = pendingTunnelFile
        pendingTunnelFile = null
        if (resultCode == Activity.RESULT_OK) {
            if (file != null) {
                SmartRuleManager.toggleManualTunnel(file, true)
            } else {
                appContext.startService(Intent(appContext, SmartAgentVpnService::class.java))
            }
        } else {
            SmartToast.showFailure(activity, "VPN 权限未授予")
        }
        return true
    }

    private fun startVpnIntent(file: String) {
        pendingTunnelFile = file
        val prepareIntent = VpnService.prepare(appContext)
        if (prepareIntent != null) {
            activity.startActivityForResult(prepareIntent, REQ_CODE_VPN_PERMISSION)
        } else {
            SmartRuleManager.toggleManualTunnel(file, true)
        }
    }
}
