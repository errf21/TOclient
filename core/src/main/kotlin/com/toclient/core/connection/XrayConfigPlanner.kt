package com.toclient.core.connection

import com.toclient.core.contract.SocksEndpoint
import com.toclient.core.model.RoutingAction
import com.toclient.core.model.RoutingRule
import com.toclient.core.model.VpnProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class XrayConfigPlanner(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    fun prepare(
        rawJson: String,
        socks: SocksEndpoint,
        profile: VpnProfile? = null,
    ): String {
        require(rawJson.toByteArray().size <= MAX_CONFIG_BYTES) { "Xray configuration is too large" }
        val root = json.parseToJsonElement(rawJson).jsonObject
        require(root["outbounds"]?.jsonArray?.isNotEmpty() == true) {
            "Xray configuration requires at least one outbound"
        }

        val priorInbounds = root["inbounds"]?.jsonArray ?: JsonArray(emptyList())
        val retained = priorInbounds.filterNot {
            runCatching { it.jsonObject["tag"] == JsonPrimitive(SOCKS_TAG) }.getOrDefault(false)
        }
        val socksInbound = buildJsonObject {
            put("tag", JsonPrimitive(SOCKS_TAG))
            put("listen", JsonPrimitive(socks.host))
            put("port", JsonPrimitive(socks.port))
            put("protocol", JsonPrimitive("socks"))
            put(
                "settings",
                buildJsonObject {
                    put("auth", JsonPrimitive("noauth"))
                    put("udp", JsonPrimitive(true))
                    put("ip", JsonPrimitive(socks.host))
                },
            )
            put(
                "sniffing",
                buildJsonObject {
                    put("enabled", JsonPrimitive(true))
                    put(
                        "destOverride",
                        JsonArray(listOf("http", "tls", "quic").map(::JsonPrimitive)),
                    )
                    put("routeOnly", JsonPrimitive(false))
                },
            )
        }

        val policy = mergeStatsPolicy(root["policy"])
        val outbounds = ensureUtilityOutbounds(root.getValue("outbounds").jsonArray)
        val proxyTag = outbounds.first().jsonObject.getValue("tag").jsonPrimitive.content
        val updated = JsonObject(
            root.toMutableMap().apply {
                put("inbounds", JsonArray(listOf(socksInbound) + retained))
                put("outbounds", outbounds)
                put("policy", policy)
                putIfAbsent("stats", JsonObject(emptyMap()))
                profile?.let {
                    put(
                        "dns",
                        buildJsonObject {
                            put("servers", JsonArray(it.dns.servers.map(::JsonPrimitive)))
                            put("queryStrategy", JsonPrimitive(if (it.ipv6Enabled) "UseIP" else "UseIPv4"))
                        },
                    )
                    put("routing", mergeRouting(root["routing"], it.routing.rules, proxyTag))
                }
            },
        )
        return json.encodeToString(JsonElement.serializer(), updated)
    }

    private fun ensureUtilityOutbounds(existing: JsonArray): JsonArray {
        val values = existing.mapIndexed { index, element ->
            if (index != 0) element else {
                val outbound = element.jsonObject
                if ("tag" in outbound) outbound
                else JsonObject(outbound.toMutableMap().apply { put("tag", JsonPrimitive("proxy")) })
            }
        }.toMutableList()
        val tags = values.mapNotNull {
            runCatching { it.jsonObject["tag"]?.jsonPrimitive?.content }.getOrNull()
        }.toSet()
        if ("direct" !in tags) {
            values += buildJsonObject {
                put("tag", JsonPrimitive("direct"))
                put("protocol", JsonPrimitive("freedom"))
            }
        }
        if ("block" !in tags) {
            values += buildJsonObject {
                put("tag", JsonPrimitive("block"))
                put("protocol", JsonPrimitive("blackhole"))
            }
        }
        return JsonArray(values)
    }

    private fun mergeRouting(
        existing: JsonElement?,
        profileRules: List<RoutingRule>,
        proxyTag: String,
    ): JsonObject {
        val routing = existing?.let { runCatching { it.jsonObject }.getOrNull() }
            ?: JsonObject(emptyMap())
        val existingRules = routing["rules"]?.let { runCatching { it.jsonArray }.getOrNull() }
            ?: JsonArray(emptyList())
        val rendered = profileRules.map { rule ->
            buildJsonObject {
                put("type", JsonPrimitive("field"))
                if (rule.domains.isNotEmpty()) {
                    put("domain", JsonArray(rule.domains.map {
                        JsonPrimitive(normalizeDomainRule(it))
                    }))
                }
                if (rule.cidrs.isNotEmpty()) {
                    put("ip", JsonArray(rule.cidrs.map(::JsonPrimitive)))
                }
                put(
                    "outboundTag",
                    JsonPrimitive(
                        when (rule.action) {
                            RoutingAction.PROXY -> proxyTag
                            RoutingAction.DIRECT -> "direct"
                            RoutingAction.BLOCK -> "block"
                        },
                    ),
                )
            }
        }
        return JsonObject(
            routing.toMutableMap().apply {
                putIfAbsent("domainStrategy", JsonPrimitive("IPIfNonMatch"))
                put("rules", JsonArray(rendered + existingRules))
            },
        )
    }

    private fun normalizeDomainRule(value: String): String {
        val trimmed = value.trim()
        require(trimmed.isNotEmpty())
        return if (trimmed.substringBefore(':') in DOMAIN_RULE_PREFIXES) trimmed
        else "domain:$trimmed"
    }

    private fun mergeStatsPolicy(existing: JsonElement?): JsonObject {
        val policy = existing?.let { runCatching { it.jsonObject }.getOrNull() } ?: JsonObject(emptyMap())
        val system = policy["system"]?.let { runCatching { it.jsonObject }.getOrNull() }
            ?: JsonObject(emptyMap())
        val updatedSystem = JsonObject(
            system.toMutableMap().apply {
                put("statsOutboundUplink", JsonPrimitive(true))
                put("statsOutboundDownlink", JsonPrimitive(true))
            },
        )
        return JsonObject(policy.toMutableMap().apply { put("system", updatedSystem) })
    }

    companion object {
        const val SOCKS_TAG = "to-client-socks"
        const val MAX_CONFIG_BYTES = 2 * 1024 * 1024
        private val DOMAIN_RULE_PREFIXES = setOf("domain", "full", "regexp", "geosite", "keyword")
    }
}