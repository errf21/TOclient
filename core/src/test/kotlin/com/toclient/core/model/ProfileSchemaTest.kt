package com.toclient.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.Json

class ProfileSchemaTest {
    @Test
    fun `phase 2 profile json migrates through schema defaults`() {
        val phase2Json = """
            {
              "id":"legacy-profile",
              "name":"Legacy",
              "engine":"XRAY",
              "secretRef":"legacy-secret",
              "dns":{"servers":["1.1.1.1"],"allowFallbackOutsideTunnel":false},
              "routing":{"mode":"FULL_TUNNEL","bypassLan":true,"excludedCidrs":[]},
              "perApp":{"mode":"ALL_APPS","packages":[]},
              "mtu":1500,
              "ipv6Enabled":false,
              "reconnect":{"enabled":true,"maxAttempts":5,"initialDelayMillis":1000,
                "maxDelayMillis":30000},
              "enabled":true
            }
        """.trimIndent()

        val profile = Json.decodeFromString(VpnProfile.serializer(), phase2Json)

        assertEquals(VpnProfile.CURRENT_PROFILE_SCHEMA, profile.schemaVersion)
        assertIs<ProfileSource.Manual>(profile.source)
        assertEquals(null, profile.fingerprint)
        assertEquals("Xray", profile.protocolLabel)
        assertEquals(null, profile.endpointLabel)
        assertEquals(emptyList(), profile.routing.rules)
    }
}