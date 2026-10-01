package com.toclient.core.connection

import com.toclient.core.model.ProfileId

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data class Preparing(val profileId: ProfileId) : ConnectionState
    data class StartingEngine(val profileId: ProfileId) : ConnectionState
    data class EstablishingTun(val profileId: ProfileId) : ConnectionState
    data class StartingBridge(val profileId: ProfileId) : ConnectionState
    data class Connected(
        val profileId: ProfileId,
        val connectedAtMillis: Long,
        val networkGeneration: Long,
    ) : ConnectionState
    data class Reconnecting(
        val profileId: ProfileId,
        val attempt: Int,
        val reason: String,
    ) : ConnectionState
    data class Stopping(val profileId: ProfileId?) : ConnectionState
    data class Failed(
        val profileId: ProfileId?,
        val reason: FailureReason,
        val message: String,
        val recoverable: Boolean,
    ) : ConnectionState
}

enum class FailureReason {
    PROFILE_NOT_FOUND,
    PROFILE_DISABLED,
    INVALID_CONFIGURATION,
    ENGINE_START_FAILED,
    TUN_ESTABLISH_FAILED,
    BRIDGE_START_FAILED,
    RECONNECT_EXHAUSTED,
    INTERNAL_ERROR,
}