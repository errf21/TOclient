package com.toclient.core.connection

import com.toclient.core.contract.SocksEndpoint
import com.toclient.core.model.DnsPolicy
import com.toclient.core.model.ProfileId
import com.toclient.core.model.RoutingAction
import com.toclient.core.model.RoutingPolicy
import com.toclient.core.model.RoutingRule
import com.toclient.core.model.VpnProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class XrayConfigPlannerTest {
    private val planner = XrayConfigPlanner()

    @Test
    fun `injects private socks inbound and statistics policy`() {
        val result = planner.prepare(
            """{"inbounds":[],"outbounds":[{"tag":"proxy","protocol":"freedom"}]}""",
            SocksEndpoint(port = 11_111),
        )
        val root = Json.parseToJsonElement(result).jsonObject
        val inbound = root.getValue("inbounds").jsonArray.first().jsonObject

        assertEquals("to-client-socks", inbound.getValue("tag").jsonPrimitive.content)
        assertEquals(11_111, inbound.getValue("port").jsonPrimitive.content.toInt())
        assertEquals(
            "true",
            root.getValue("policy").jsonObject
                .getValue("system").jsonObject
                .getValue("statsOutboundUplink").jsonPrimitive.content,
        )
        assertTrue("stats" in root)
    }

    @Test
    fun `rejects configuration without outbound`() {
        assertFailsWith<IllegalArgumentException> {
            planner.prepare("""{"inbounds":[]}""", SocksEndpoint())
        }
    }

    @Test
    fun `renders leak safe dns and typed routing rules`() {
        val profile = VpnProfile(
            id = ProfileId("routed"),
            name = "Routed",
            secretRef = "secret",
            dns = DnsPolicy(listOf("9.9.9.9")),
            routing = RoutingPolicy(
                rules = listOf(
                    RoutingRule(RoutingAction.DIRECT, domains = listOf("lan.example")),
                    RoutingRule(RoutingAction.BLOCK, cidrs = listOf("203.0.113.0/24")),
                    RoutingRule(RoutingAction.PROXY, domains = listOf("full:secure.example")),
                ),
            ),
        )
        val result = planner.prepare(
            """{"outbounds":[{"protocol":"vless","settings":{}}]}""",
            SocksEndpoint(),
            profile,
        )
        val root = Json.parseToJsonElement(result).jsonObject
        val dns = root.getValue("dns").jsonObject
        val rules = root.getValue("routing").jsonObject.getValue("rules").jsonArray

        assertEquals("9.9.9.9", dns.getValue("servers").jsonArray.single().jsonPrimitive.content)
        assertEquals("UseIPv4", dns.getValue("queryStrategy").jsonPrimitive.content)
        assertEquals("domain:lan.example", rules[0].jsonObject
            .getValue("domain").jsonArray.single().jsonPrimitive.content)
        assertEquals("direct", rules[0].jsonObject.getValue("outboundTag").jsonPrimitive.content)
        assertEquals("block", rules[1].jsonObject.getValue("outboundTag").jsonPrimitive.content)
        assertEquals("proxy", rules[2].jsonObject.getValue("outboundTag").jsonPrimitive.content)
        assertTrue(root.getValue("outbounds").jsonArray.any {
            it.jsonObject["tag"]?.jsonPrimitive?.content == "direct"
        })
    }
}