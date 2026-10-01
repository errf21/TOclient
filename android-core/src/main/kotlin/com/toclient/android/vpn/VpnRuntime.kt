package com.toclient.android.vpn

import com.toclient.core.connection.ConnectionCoordinator
import com.toclient.core.connection.ConnectionState
import com.toclient.core.model.TrafficSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-local, read-only UI bridge for the foreground VPN service.
 *
 * The service remains the sole owner of the engine, TUN descriptor and lifecycle.
 * This object exposes truthful live state without introducing a second controller.
 */
object VpnRuntime {
    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    private val mutableTraffic = MutableStateFlow<TrafficSnapshot?>(null)
    private var coordinator: ConnectionCoordinator? = null

    val state: StateFlow<ConnectionState> = mutableState.asStateFlow()
    val traffic: StateFlow<TrafficSnapshot?> = mutableTraffic.asStateFlow()

    internal fun attach(value: ConnectionCoordinator) {
        coordinator = value
    }

    internal fun detach(value: ConnectionCoordinator) {
        if (coordinator === value) coordinator = null
        mutableState.value = ConnectionState.Idle
        mutableTraffic.value = null
    }

    internal fun publishState(value: ConnectionState) {
        mutableState.value = value
        if (value !is ConnectionState.Connected) mutableTraffic.value = null
    }

    internal fun publishTraffic(value: TrafficSnapshot) {
        mutableTraffic.value = value
    }

    suspend fun refreshTraffic(): TrafficSnapshot? =
        coordinator?.traffic()?.also(::publishTraffic)

    suspend fun measureDelay(url: String): Long =
        coordinator?.measureDelay(url) ?: error("VPN service is not running")
}