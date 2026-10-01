package com.toclient.android.engine

import kotlin.test.assertEquals
import org.junit.Test

class XrayTrafficParserTest {
    @Test
    fun parsesAndAggregatesOutboundCounters() {
        val result = XrayTrafficParser.parse(
            "proxy,uplink,100;proxy,downlink,250;proxy,uplink,50;direct,downlink,7;",
        )

        assertEquals(2, result.size)
        assertEquals(150, result.first { it.tag == "proxy" }.traffic.uploadedBytes)
        assertEquals(250, result.first { it.tag == "proxy" }.traffic.downloadedBytes)
        assertEquals(7, result.first { it.tag == "direct" }.traffic.downloadedBytes)
    }
}