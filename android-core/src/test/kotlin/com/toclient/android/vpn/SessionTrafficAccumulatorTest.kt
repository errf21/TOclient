package com.toclient.android.vpn

import com.toclient.core.model.TrafficCounter
import com.toclient.core.model.TrafficSnapshot
import kotlin.test.assertEquals
import org.junit.Test

class SessionTrafficAccumulatorTest {
    @Test
    fun `sums every reset-on-read engine traffic sample`() {
        val accumulator = SessionTrafficAccumulator()
        assertEquals(150, accumulator.accumulate(snapshot(100, 150)).engineTotal.downloadedBytes)
        assertEquals(330, accumulator.accumulate(snapshot(120, 180)).engineTotal.downloadedBytes)
        val total = accumulator.accumulate(snapshot(10, 20)).engineTotal
        assertEquals(230, total.uploadedBytes)
        assertEquals(350, total.downloadedBytes)
    }

    @Test
    fun `reset starts a new session`() {
        val accumulator = SessionTrafficAccumulator()
        accumulator.accumulate(snapshot(100, 200))
        accumulator.reset()
        val fresh = accumulator.accumulate(snapshot(5, 7)).engineTotal
        assertEquals(5, fresh.uploadedBytes)
        assertEquals(7, fresh.downloadedBytes)
    }

    private fun snapshot(upload: Long, download: Long) = TrafficSnapshot(
        capturedAtMillis = 1,
        tun = TrafficCounter(),
        engineTotal = TrafficCounter(upload, download),
        engineOutbounds = emptyList(),
    )
}