package com.toclient.core.routing

import com.toclient.core.model.RoutingPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Ipv4RoutePlannerTest {
    @Test
    fun `full tunnel without exclusions is a default route`() {
        assertEquals(
            listOf(Ipv4Cidr("0.0.0.0", 0)),
            Ipv4RoutePlanner.includedRoutes(RoutingPolicy(bypassLan = false)),
        )
    }

    @Test
    fun `LAN bypass uses positive routes and excludes private ranges`() {
        val routes = Ipv4RoutePlanner.includedRoutes(RoutingPolicy(bypassLan = true))

        assertTrue(routes.isNotEmpty())
        assertFalse(routes.contains(Ipv4Cidr("0.0.0.0", 0)))
        assertFalse(isCovered("10.0.0.1", routes))
        assertFalse(isCovered("192.168.1.1", routes))
        assertTrue(isCovered("1.1.1.1", routes))
        assertTrue(isCovered("8.8.8.8", routes))
    }

    @Test
    fun `custom excluded CIDR is removed from positive route set`() {
        val routes = Ipv4RoutePlanner.includedRoutes(
            RoutingPolicy(bypassLan = false, excludedCidrs = listOf("203.0.113.0/24")),
        )

        assertFalse(isCovered("203.0.113.5", routes))
        assertTrue(isCovered("203.0.114.5", routes))
    }

    private fun isCovered(address: String, routes: List<Ipv4Cidr>): Boolean {
        val value = address.split('.').fold(0L) { acc, part -> (acc shl 8) or part.toLong() }
        return routes.any { route ->
            val network = route.address.split('.').fold(0L) { acc, part -> (acc shl 8) or part.toLong() }
            val mask = if (route.prefixLength == 0) 0L
            else (0xffff_ffffL shl (32 - route.prefixLength)) and 0xffff_ffffL
            value and mask == network and mask
        }
    }
}