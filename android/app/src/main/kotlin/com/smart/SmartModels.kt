package com.smart

import kotlinx.serialization.Serializable

enum class RuleType {
    WIFI_SSID,
    IPV6_AVAILABLE,
    IPV4_AVAILABLE
}

@Serializable
data class AgentRule(
    val id: String,
    val type: RuleType,
    val value: String?, // SSID for WIFI, null for others
    val tunnelFile: String, // Tunnel config filename
    val tunnelName: String, // Human readable name
    val enabled: Boolean = true
)

@Serializable
data class AgentRuleConfig(
    val enabled: Boolean = false,
    val rules: List<AgentRule> = emptyList()
)

@Serializable
data class AppRuleConfig(
    val enabled: Boolean = false,
    val selectedApps: List<String> = emptyList(),
    val version: Int = 0
)
