package com.toclient.core.routing

import com.toclient.core.model.DnsPolicy
import com.toclient.core.model.PerAppMode
import com.toclient.core.model.PerAppPolicy
import com.toclient.core.model.ProfileId
import com.toclient.core.model.RoutingAction
import com.toclient.core.model.RoutingPolicy
import com.toclient.core.model.RoutingRule
import com.toclient.core.model.VpnProfile
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PolicyValidatorTest {
    @Test
    fun `accepts numeric DNS and Android package names`() {
        PolicyValidator.validate(
            profile(
                perApp = PerAppPolicy(
                    mode = PerAppMode.BYPASS_SELECTED,
                    packages = setOf("com.example.browser"),
                ),
            ),
        )
    }

    @Test
    fun `rejects hostname DNS to prevent system-side resolution`() {
        assertFailsWith<IllegalArgumentException> {
            PolicyValidator.validate(profile(dns = DnsPolicy(listOf("dns.example"))))
        }
    }

    @Test
    fun `rejects empty selected-app policy`() {
        assertFailsWith<IllegalArgumentException> {
            PolicyValidator.validate(
                profile(perApp = PerAppPolicy(mode = PerAppMode.ONLY_SELECTED)),
            )
        }
    }

    @Test
    fun `rejects malformed ipv4 and ipv6 cidr rules`() {
        listOf("999.1.1.1/24", "192.0.2.0/33", "not-an-ip/24", "2001:db8::/129")
            .forEach { cidr ->
                assertFailsWith<IllegalArgumentException> {
                    PolicyValidator.validate(
                        profile(
                            routing = RoutingPolicy(
                                rules = listOf(
                                    RoutingRule(RoutingAction.BLOCK, cidrs = listOf(cidr)),
                                ),
                            ),
                        ),
                    )
                }
            }
    }

    @Test
    fun `rejects malformed excluded tun routes`() {
        assertFailsWith<IllegalArgumentException> {
            PolicyValidator.validate(
                profile(routing = RoutingPolicy(excludedCidrs = listOf("192.0.2.0/99"))),
            )
        }
    }

    private fun profile(
        dns: DnsPolicy = DnsPolicy(),
        perApp: PerAppPolicy = PerAppPolicy(),
        routing: RoutingPolicy = RoutingPolicy(),
    ) = VpnProfile(
        id = ProfileId("routing-test"),
        name = "Routing test",
        secretRef = "routing-secret",
        dns = dns,
        perApp = perApp,
        routing = routing,
    )
}