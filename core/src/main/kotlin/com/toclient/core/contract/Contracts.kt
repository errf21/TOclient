package com.toclient.core.contract

import com.toclient.core.model.ProfileId
import com.toclient.core.model.SensitiveProfileConfig
import com.toclient.core.model.StoredProfile
import com.toclient.core.model.TaggedTrafficCounter
import com.toclient.core.model.TrafficCounter
import com.toclient.core.model.VpnProfile

data class SocksEndpoint(
    val host: String = "127.0.0.1",
    val port: Int = 10_808,
) {
    init {
        require(port in 1..65_535)
    }
}

data class EngineStartRequest(
    val xrayJson: String,
    val socksEndpoint: SocksEndpoint,
)

interface EngineAdapter {
    val id: String
    suspend fun start(request: EngineStartRequest)
    suspend fun stop()
    suspend fun isRunning(): Boolean
    suspend fun traffic(): List<TaggedTrafficCounter>
    suspend fun measureDelay(url: String): Long =
        throw UnsupportedOperationException("Latency measurement is not supported by $id")
}

data class TunBridgeStartRequest(
    val tunFileDescriptor: Int,
    val mtu: Int,
    val ipv4Address: String,
    val ipv6Address: String?,
    val socksEndpoint: SocksEndpoint,
)

interface TunBridge {
    val id: String
    suspend fun start(request: TunBridgeStartRequest)
    suspend fun stop()
    suspend fun isRunning(): Boolean
    suspend fun traffic(): TrafficCounter
}

interface TunDevice : AutoCloseable {
    val fileDescriptor: Int
    override fun close()
}

interface TunDeviceFactory {
    suspend fun establish(profile: VpnProfile): TunDevice
}

interface ProfileRepository {
    suspend fun upsert(profile: VpnProfile, sensitive: SensitiveProfileConfig)
    suspend fun get(profileId: ProfileId): VpnProfile?
    suspend fun getSensitive(profile: VpnProfile): SensitiveProfileConfig
    suspend fun list(): List<VpnProfile>
    suspend fun delete(profileId: ProfileId)
    suspend fun replaceSubscriptionProfiles(
        subscriptionId: String,
        profiles: List<StoredProfile>,
    )
}

fun interface Sleeper {
    suspend fun sleep(millis: Long)
}