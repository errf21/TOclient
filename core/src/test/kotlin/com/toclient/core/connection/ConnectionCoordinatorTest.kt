package com.toclient.core.connection

import com.toclient.core.contract.EngineAdapter
import com.toclient.core.contract.EngineStartRequest
import com.toclient.core.contract.ProfileRepository
import com.toclient.core.contract.Sleeper
import com.toclient.core.contract.TunBridge
import com.toclient.core.contract.TunBridgeStartRequest
import com.toclient.core.contract.TunDevice
import com.toclient.core.contract.TunDeviceFactory
import com.toclient.core.model.ProfileId
import com.toclient.core.model.SensitiveProfileConfig
import com.toclient.core.model.TaggedTrafficCounter
import com.toclient.core.model.StoredProfile
import com.toclient.core.model.TrafficCounter
import com.toclient.core.model.VpnProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ConnectionCoordinatorTest {
    @Test
    fun `starts in order and cleans up in reverse order`() = runTest {
        val events = mutableListOf<String>()
        val engine = FakeEngine(events)
        val bridge = FakeBridge(events)
        val tun = FakeTunFactory(events)
        val coordinator = coordinator(engine, bridge, tun)

        coordinator.connect(PROFILE_ID)
        assertIs<ConnectionState.Connected>(coordinator.state.value)
        assertEquals(listOf("engine.start", "tun.open", "bridge.start"), events)

        coordinator.disconnect()
        assertEquals(
            listOf(
                "engine.start",
                "tun.open",
                "bridge.start",
                "bridge.stop",
                "tun.close",
                "engine.stop",
            ),
            events,
        )
        assertEquals(ConnectionState.Idle, coordinator.state.value)
    }

    @Test
    fun `bridge failure closes tun and stops engine`() = runTest {
        val events = mutableListOf<String>()
        val engine = FakeEngine(events)
        val bridge = FakeBridge(events, failStart = true)
        val tun = FakeTunFactory(events)
        val coordinator = coordinator(engine, bridge, tun)

        val error = assertFailsWith<ConnectionFailure> {
            coordinator.connect(PROFILE_ID)
        }
        assertEquals(FailureReason.BRIDGE_START_FAILED, error.reason)
        assertEquals(
            listOf(
                "engine.start",
                "tun.open",
                "bridge.start",
                "bridge.stop",
                "tun.close",
                "engine.stop",
            ),
            events,
        )
        assertFalse(engine.running)
        assertFalse(bridge.running)
    }

    @Test
    fun `network change performs a full bounded reconnect`() = runTest {
        val events = mutableListOf<String>()
        val engine = FakeEngine(events)
        val bridge = FakeBridge(events)
        val tun = FakeTunFactory(events)
        val coordinator = coordinator(engine, bridge, tun)

        coordinator.connect(PROFILE_ID)
        coordinator.handleNetworkChanged("wifi to cellular")

        val state = assertIs<ConnectionState.Connected>(coordinator.state.value)
        assertEquals(1, state.networkGeneration)
        assertEquals(2, events.count { it == "engine.start" })
        assertEquals(2, events.count { it == "bridge.start" })
        assertTrue(engine.running)
        assertTrue(bridge.running)
    }

    private fun coordinator(
        engine: FakeEngine,
        bridge: FakeBridge,
        tun: FakeTunFactory,
    ) = ConnectionCoordinator(
        profiles = FakeProfiles(),
        engine = engine,
        tunFactory = tun,
        bridge = bridge,
        configPlanner = XrayConfigPlanner(),
        sleeper = Sleeper { },
        nowMillis = { 1234L },
    )

    private class FakeProfiles : ProfileRepository {
        private val profile = VpnProfile(
            id = PROFILE_ID,
            name = "Test",
            secretRef = "test-secret",
        )

        override suspend fun upsert(profile: VpnProfile, sensitive: SensitiveProfileConfig) = Unit
        override suspend fun get(profileId: ProfileId): VpnProfile? =
            profile.takeIf { it.id == profileId }
        override suspend fun getSensitive(profile: VpnProfile) =
            SensitiveProfileConfig("""{"outbounds":[{"protocol":"freedom"}]}""")
        override suspend fun list(): List<VpnProfile> = listOf(profile)
        override suspend fun delete(profileId: ProfileId) = Unit
        override suspend fun replaceSubscriptionProfiles(
            subscriptionId: String,
            profiles: List<StoredProfile>,
        ) = Unit
    }

    private class FakeEngine(
        private val events: MutableList<String>,
    ) : EngineAdapter {
        override val id = "fake-engine"
        var running = false

        override suspend fun start(request: EngineStartRequest) {
            events += "engine.start"
            running = true
        }

        override suspend fun stop() {
            events += "engine.stop"
            running = false
        }

        override suspend fun isRunning() = running
        override suspend fun traffic(): List<TaggedTrafficCounter> = emptyList()
    }

    private class FakeBridge(
        private val events: MutableList<String>,
        private val failStart: Boolean = false,
    ) : TunBridge {
        override val id = "fake-bridge"
        var running = false

        override suspend fun start(request: TunBridgeStartRequest) {
            events += "bridge.start"
            if (failStart) error("bridge error")
            running = true
        }

        override suspend fun stop() {
            events += "bridge.stop"
            running = false
        }

        override suspend fun isRunning() = running
        override suspend fun traffic() = TrafficCounter()
    }

    private class FakeTunFactory(
        private val events: MutableList<String>,
    ) : TunDeviceFactory {
        override suspend fun establish(profile: VpnProfile): TunDevice {
            events += "tun.open"
            return object : TunDevice {
                override val fileDescriptor = 42
                override fun close() {
                    events += "tun.close"
                }
            }
        }
    }

    companion object {
        private val PROFILE_ID = ProfileId("test-profile")
    }
}