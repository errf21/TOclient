package com.toclient.core.routing

import com.toclient.core.model.PerAppMode
import com.toclient.core.model.VpnProfile

object PolicyValidator {
    fun validate(profile: VpnProfile) {
        require(profile.dns.servers.isNotEmpty()) { "At least one DNS server is required" }
        profile.dns.servers.forEach { value ->
            require(isNumericAddress(value)) { "Android TUN DNS must be a numeric address: $value" }
        }
        when (profile.perApp.mode) {
            PerAppMode.ALL_APPS -> require(profile.perApp.packages.isEmpty()) {
                "ALL_APPS must not contain package entries"
            }
            PerAppMode.ONLY_SELECTED,
            PerAppMode.BYPASS_SELECTED,
            -> require(profile.perApp.packages.isNotEmpty()) {
                "Selected-app mode requires at least one package"
            }
        }
        profile.perApp.packages.forEach {
            require(it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) {
                "Invalid Android package name: $it"
            }
        }
        profile.routing.rules.forEach { rule ->
            rule.domains.forEach {
                require(it.length in 1..512 && !it.any(Char::isWhitespace)) {
                    "Invalid domain routing rule"
                }
            }
            rule.cidrs.forEach {
                require(isCidr(it)) { "Invalid CIDR routing rule: $it" }
            }
        }
        profile.routing.excludedCidrs.forEach {
            require(isCidr(it)) { "Invalid excluded CIDR: $it" }
        }
    }

    private fun isNumericAddress(value: String): Boolean {
        if (value.contains(':')) {
            if (!value.matches(Regex("[0-9A-Fa-f:]+")) || value.count { it == ':' } < 2) {
                return false
            }
            return runCatching {
                java.net.InetAddress.getByName(value) is java.net.Inet6Address
            }.getOrDefault(false)
        }
        val parts = value.split('.')
        return parts.size == 4 && parts.all {
            it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) && it.toInt() in 0..255
        }
    }

    private fun isCidr(value: String): Boolean {
        if (value.length !in 3..64) return false
        val parts = value.split('/')
        if (parts.size != 2) return false
        val prefix = parts[1].toIntOrNull() ?: return false
        return if (parts[0].contains(':')) {
            prefix in 0..128 && isNumericAddress(parts[0])
        } else {
            prefix in 0..32 && isNumericAddress(parts[0])
        }
    }
}