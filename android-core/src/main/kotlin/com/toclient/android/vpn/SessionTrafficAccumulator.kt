package com.toclient.android.vpn

import com.toclient.core.model.TrafficCounter
import com.toclient.core.model.TrafficSnapshot

/**
 * Accumulates the reset-on-read traffic deltas returned by AndroidLibXrayLite
 * into user-visible totals for the current VPN session.
 */
internal class SessionTrafficAccumulator {
    private var uploadedBytes = 0L
    private var downloadedBytes = 0L

    fun reset() {
        uploadedBytes = 0
        downloadedBytes = 0
    }

    fun accumulate(snapshot: TrafficSnapshot): TrafficSnapshot {
        val raw = snapshot.engineTotal
        uploadedBytes += raw.uploadedBytes
        downloadedBytes += raw.downloadedBytes
        return snapshot.copy(
            engineTotal = TrafficCounter(
                uploadedBytes = uploadedBytes,
                downloadedBytes = downloadedBytes,
            ),
        )
    }
}