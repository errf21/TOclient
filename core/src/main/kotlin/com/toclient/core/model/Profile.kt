package com.toclient.core.model

import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class ProfileId(val value: String) {
    init {
        require(value.matches(Regex("[A-Za-z0-9._-]{1,96}"))) { "Invalid profile id" }
    }
}

@Serializable
data class VpnProfile(
    val id: ProfileId,
    val name: String,
    val schemaVersion: Int = CURRENT_PROFILE_SCHEMA,
    val engine: EngineKind = EngineKind.XRAY,
    val secretRef: String,
    val source: ProfileSource = ProfileSource.Manual,
    val fingerprint: String? = null,
    val protocolLabel: String = "Xray",
    val endpointLabel: String? = null,
    val transportLabel: String? = null,
    val securityLabel: String? = null,
    val dns: DnsPolicy = DnsPolicy(),
    val routing: RoutingPolicy = RoutingPolicy(),
    val perApp: PerAppPolicy = PerAppPolicy(),
    val mtu: Int = 1_500,
    val ipv6Enabled: Boolean = false,
    val reconnect: ReconnectPolicy = ReconnectPolicy(),
    val enabled: Boolean = true,
    val isFavorite: Boolean = false,
    val latencyMillis: Long? = null,
    val lastTestedMillis: Long? = null,
    val latencyStatus: LatencyTestStatus = LatencyTestStatus.NOT_TESTED,
    val latencyError: String? = null,
) {
    init {
        require(name.isNotBlank()) { "Profile name is required" }
        require(schemaVersion in 1..CURRENT_PROFILE_SCHEMA) { "Unsupported profile schema" }
        require(mtu in 1_280..9_000) { "MTU must be in 1280..9000" }
        require(secretRef.isNotBlank()) { "Secret reference is required" }
        require(protocolLabel.isNotBlank()) { "Protocol label is required" }
        require(latencyMillis == null || latencyMillis >= 0) { "Latency cannot be negative" }
        require(latencyError == null || latencyError.length <= 200) { "Latency error is too long" }
    }

    companion object {
        const val CURRENT_PROFILE_SCHEMA = 1
    }
}

@Serializable
enum class EngineKind { XRAY }

@Serializable
enum class LatencyTestStatus { NOT_TESTED, SUCCESS, TIMEOUT, FAILED }

@Serializable
sealed interface ProfileSource {
    @Serializable
    data object Manual : ProfileSource

    @Serializable
    data class Subscription(val subscriptionId: String) : ProfileSource {
        init {
            require(subscriptionId.matches(Regex("[A-Za-z0-9._-]{1,96}")))
        }
    }
}

@Serializable
data class DnsPolicy(
    val servers: List<String> = listOf("1.1.1.1", "1.0.0.1"),
    val allowFallbackOutsideTunnel: Boolean = false,
)

@Serializable
data class RoutingPolicy(
    val mode: RouteMode = RouteMode.FULL_TUNNEL,
    val bypassLan: Boolean = true,
    val excludedCidrs: List<String> = emptyList(),
    val rules: List<RoutingRule> = emptyList(),
)

@Serializable
enum class RouteMode { FULL_TUNNEL }

@Serializable
data class RoutingRule(
    val action: RoutingAction,
    val domains: List<String> = emptyList(),
    val cidrs: List<String> = emptyList(),
) {
    init {
        require(domains.isNotEmpty() || cidrs.isNotEmpty()) { "Routing rule is empty" }
    }
}

@Serializable
enum class RoutingAction { PROXY, DIRECT, BLOCK }

@Serializable
data class PerAppPolicy(
    val mode: PerAppMode = PerAppMode.ALL_APPS,
    val packages: Set<String> = emptySet(),
)

@Serializable
enum class PerAppMode {
    ALL_APPS,
    ONLY_SELECTED,
    BYPASS_SELECTED,
}

@Serializable
data class ReconnectPolicy(
    val enabled: Boolean = true,
    val maxAttempts: Int = 5,
    val initialDelayMillis: Long = 1_000,
    val maxDelayMillis: Long = 30_000,
) {
    init {
        require(maxAttempts in 0..20)
        require(initialDelayMillis in 0..60_000)
        require(maxDelayMillis >= initialDelayMillis)
    }
}

data class SensitiveProfileConfig(
    val xrayJson: String,
)

data class StoredProfile(
    val profile: VpnProfile,
    val sensitive: SensitiveProfileConfig,
)