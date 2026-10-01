package com.toclient.core.connection

import com.toclient.core.contract.EngineAdapter
import com.toclient.core.contract.EngineStartRequest
import com.toclient.core.contract.ProfileRepository
import com.toclient.core.contract.Sleeper
import com.toclient.core.contract.SocksEndpoint
import com.toclient.core.contract.TunBridge
import com.toclient.core.contract.TunBridgeStartRequest
import com.toclient.core.contract.TunDevice
import com.toclient.core.contract.TunDeviceFactory
import com.toclient.core.model.ProfileId
import com.toclient.core.model.TrafficSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ConnectionCoordinator(
    private val profiles: ProfileRepository,
    private val engine: EngineAdapter,
    private val tunFactory: TunDeviceFactory,
    private val bridge: TunBridge,
    private val configPlanner: XrayConfigPlanner,
    private val sleeper: Sleeper,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val socksEndpoint: SocksEndpoint = SocksEndpoint(),
) {
    private val operationMutex = Mutex()
    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    private var activeProfileId: ProfileId? = null
    private var tunDevice: TunDevice? = null
    private var networkGeneration = 0L

    val state: StateFlow<ConnectionState> = mutableState.asStateFlow()

    suspend fun connect(profileId: ProfileId): Unit = operationMutex.withLock {
        if (activeProfileId != null || tunDevice != null || engine.isRunning() || bridge.isRunning()) {
            stopResources(updateState = activeProfileId != null)
        }
        activeProfileId = profileId
        startOnce(profileId)
    }

    suspend fun disconnect(): Unit = operationMutex.withLock {
        stopResources(updateState = true)
        activeProfileId = null
        mutableState.value = ConnectionState.Idle
    }

    suspend fun handleNetworkChanged(reason: String): Unit = operationMutex.withLock {
        val profileId = activeProfileId ?: return
        val profile = profiles.get(profileId) ?: run {
            stopResources(updateState = false)
            mutableState.value = ConnectionState.Failed(
                profileId,
                FailureReason.PROFILE_NOT_FOUND,
                "Profile disappeared during reconnect",
                false,
            )
            return
        }
        if (!profile.reconnect.enabled) return

        stopResources(updateState = false)
        var delayMillis = profile.reconnect.initialDelayMillis
        var lastError: Throwable? = null
        for (attempt in 1..profile.reconnect.maxAttempts) {
            mutableState.value = ConnectionState.Reconnecting(profileId, attempt, reason)
            if (delayMillis > 0) sleeper.sleep(delayMillis)
            try {
                networkGeneration++
                startOnce(profileId)
                return
            } catch (error: Throwable) {
                lastError = error
                stopResources(updateState = false)
                delayMillis = (delayMillis * 2).coerceAtMost(profile.reconnect.maxDelayMillis)
            }
        }
        mutableState.value = ConnectionState.Failed(
            profileId,
            FailureReason.RECONNECT_EXHAUSTED,
            lastError?.message ?: "Reconnect attempts exhausted",
            true,
        )
    }

    suspend fun traffic(): TrafficSnapshot {
        val tun = bridge.traffic()
        val outbounds = engine.traffic()
        val total = outbounds.fold(com.toclient.core.model.TrafficCounter()) { acc, item ->
            com.toclient.core.model.TrafficCounter(
                uploadedBytes = acc.uploadedBytes + item.traffic.uploadedBytes,
                downloadedBytes = acc.downloadedBytes + item.traffic.downloadedBytes,
            )
        }
        return TrafficSnapshot(nowMillis(), tun, total, outbounds)
    }

    suspend fun measureDelay(url: String): Long {
        check(mutableState.value is ConnectionState.Connected) {
            "VPN is not connected"
        }
        return engine.measureDelay(url)
    }

    private suspend fun startOnce(profileId: ProfileId) {
        mutableState.value = ConnectionState.Preparing(profileId)
        val profile = profiles.get(profileId)
            ?: fail(profileId, FailureReason.PROFILE_NOT_FOUND, "Profile not found", false)
        if (!profile.enabled) {
            fail(profileId, FailureReason.PROFILE_DISABLED, "Profile is disabled", false)
        }
        val sensitive = try {
            profiles.getSensitive(profile)
        } catch (error: Throwable) {
            fail(
                profileId,
                FailureReason.INVALID_CONFIGURATION,
                error.message ?: "Unable to decrypt profile configuration",
                false,
            )
        }
        val plannedConfig = try {
            configPlanner.prepare(sensitive.xrayJson, socksEndpoint, profile)
        } catch (error: Throwable) {
            fail(
                profileId,
                FailureReason.INVALID_CONFIGURATION,
                error.message ?: "Invalid Xray JSON",
                false,
            )
        }

        try {
            mutableState.value = ConnectionState.StartingEngine(profileId)
            engine.start(EngineStartRequest(plannedConfig, socksEndpoint))
        } catch (error: Throwable) {
            fail(profileId, FailureReason.ENGINE_START_FAILED, error.message ?: "Xray failed", true)
        }

        try {
            mutableState.value = ConnectionState.EstablishingTun(profileId)
            tunDevice = tunFactory.establish(profile)
        } catch (error: Throwable) {
            stopResources(updateState = false)
            fail(profileId, FailureReason.TUN_ESTABLISH_FAILED, error.message ?: "TUN failed", true)
        }

        try {
            mutableState.value = ConnectionState.StartingBridge(profileId)
            bridge.start(
                TunBridgeStartRequest(
                    tunFileDescriptor = checkNotNull(tunDevice).fileDescriptor,
                    mtu = profile.mtu,
                    ipv4Address = DEFAULT_TUN_IPV4,
                    ipv6Address = if (profile.ipv6Enabled) DEFAULT_TUN_IPV6 else null,
                    socksEndpoint = socksEndpoint,
                ),
            )
        } catch (error: Throwable) {
            stopResources(updateState = false)
            fail(profileId, FailureReason.BRIDGE_START_FAILED, error.message ?: "TUN bridge failed", true)
        }

        mutableState.value = ConnectionState.Connected(
            profileId = profileId,
            connectedAtMillis = nowMillis(),
            networkGeneration = networkGeneration,
        )
    }

    private suspend fun stopResources(updateState: Boolean) {
        if (updateState) mutableState.value = ConnectionState.Stopping(activeProfileId)
        runCatching { bridge.stop() }
        runCatching { tunDevice?.close() }
        tunDevice = null
        runCatching { engine.stop() }
    }

    private fun fail(
        profileId: ProfileId?,
        reason: FailureReason,
        message: String,
        recoverable: Boolean,
    ): Nothing {
        mutableState.value = ConnectionState.Failed(profileId, reason, message, recoverable)
        throw ConnectionFailure(reason, message, recoverable)
    }

    companion object {
        const val DEFAULT_TUN_IPV4 = "198.18.0.1"
        const val DEFAULT_TUN_IPV6 = "fd00:1:fd00:1::1"
    }
}

class ConnectionFailure(
    val reason: FailureReason,
    override val message: String,
    val recoverable: Boolean,
) : IllegalStateException(message)