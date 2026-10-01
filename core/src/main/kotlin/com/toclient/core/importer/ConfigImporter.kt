package com.toclient.core.importer

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ConfigImporter(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    fun import(text: String): ImportedConfig {
        val value = text.trim()
        require(value.isNotEmpty()) { "Configuration is empty" }
        require(value.toByteArray().size <= MAX_IMPORT_BYTES) { "Configuration is too large" }

        if (value.startsWith("{")) {
            val root = json.parseToJsonElement(value).jsonObject
            require(root["outbounds"] != null) { "Raw Xray JSON has no outbounds" }
            val normalized = json.encodeToString(
                JsonElement.serializer(),
                canonicalize(root),
            )
            return ImportedConfig(
                name = "Imported Xray configuration",
                xrayJson = normalized,
                fingerprint = sha256(normalized),
                protocol = "xray-json",
                endpoint = null,
            )
        }

        val endpoint = when (value.substringBefore("://").lowercase()) {
            "vless" -> parseVless(value)
            "vmess" -> parseVmess(value)
            "trojan" -> parseTrojan(value)
            "ss" -> parseShadowsocks(value)
            "socks", "socks5" -> parseUserPasswordUri(value, EndpointProtocol.SOCKS)
            "http", "https" -> parseUserPasswordUri(value, EndpointProtocol.HTTP)
            "hysteria2", "hy2" -> parseHysteria2(value)
            else -> throw IllegalArgumentException("Unsupported configuration scheme")
        }
        val canonical = json.encodeToJsonElement(EndpointDefinition.serializer(), endpoint)
            .jsonObject
            .filterKeys { it != "name" }
            .let(::JsonObject)
            .let(::canonicalize)
            .let { json.encodeToString(JsonElement.serializer(), it) }
        return ImportedConfig(
            name = endpoint.name,
            xrayJson = XrayEndpointRenderer(json).render(endpoint),
            fingerprint = sha256(canonical),
            protocol = endpoint.protocol.name.lowercase(),
            endpoint = formatEndpoint(endpoint.host, endpoint.port),
            transport = endpoint.transport.network.takeIf { it.isNotBlank() },
            security = endpoint.transport.security.takeIf { it != "none" }
                ?: endpoint.transport.realityPublicKey?.let { "reality" },
        )
    }

    private fun parseVless(value: String): EndpointDefinition {
        val uri = strictUri(value)
        val id = decode(
            uri.rawUserInfo ?: throw IllegalArgumentException("VLESS id is missing"),
        )
        runCatching { UUID.fromString(id) }.getOrElse {
            throw IllegalArgumentException("Invalid VLESS UUID")
        }
        val query = query(uri)
        return EndpointDefinition(
            protocol = EndpointProtocol.VLESS,
            name = fragmentName(uri, "VLESS ${uri.host}"),
            host = requiredHost(uri),
            port = requiredPort(uri),
            credentials = EndpointCredentials(
                id = id,
                security = query["encryption"] ?: "none",
                flow = query["flow"],
            ),
            transport = transport(query),
        )
    }

    private fun parseTrojan(value: String): EndpointDefinition {
        val uri = strictUri(value)
        val query = query(uri)
        require(query["flow"].isNullOrBlank()) {
            "Trojan flow is not supported by the bundled Xray core"
        }
        return EndpointDefinition(
            protocol = EndpointProtocol.TROJAN,
            name = fragmentName(uri, "Trojan ${uri.host}"),
            host = requiredHost(uri),
            port = requiredPort(uri),
            credentials = EndpointCredentials(password = decode(uri.rawUserInfo.orEmpty())),
            transport = transport(query, defaultSecurity = "tls"),
        ).also { require(!it.credentials.password.isNullOrEmpty()) { "Trojan password is missing" } }
    }

    private fun parseUserPasswordUri(
        value: String,
        protocol: EndpointProtocol,
    ): EndpointDefinition {
        val uri = strictUri(value)
        val userInfo = uri.rawUserInfo?.split(':', limit = 2).orEmpty()
        val query = query(uri)
        return EndpointDefinition(
            protocol = protocol,
            name = fragmentName(uri, "${protocol.name.lowercase().replaceFirstChar(Char::uppercase)} ${uri.host}"),
            host = requiredHost(uri),
            port = requiredPort(uri),
            credentials = EndpointCredentials(
                username = userInfo.getOrNull(0)?.let(::decode),
                password = userInfo.getOrNull(1)?.let(::decode),
            ),
            transport = TransportSettings(
                security = if (protocol == EndpointProtocol.HTTP && uri.scheme == "https") "tls"
                else "none",
                serverName = query["sni"],
            ),
        )
    }

    private fun parseHysteria2(value: String): EndpointDefinition {
        val uri = strictUri(value.replaceFirst("hy2://", "hysteria2://"))
        val query = query(uri)
        val obfs = query["obfs"]?.takeIf(String::isNotBlank)
        require(obfs == null || obfs == "salamander") {
            "Unsupported Hysteria2 obfuscation"
        }
        val obfsPassword = query["obfs-password"] ?: query["obfsPassword"]
        require(obfs == null || !obfsPassword.isNullOrBlank()) {
            "Hysteria2 obfuscation password is missing"
        }
        return EndpointDefinition(
            protocol = EndpointProtocol.HYSTERIA2,
            name = fragmentName(uri, "Hysteria2 ${uri.host}"),
            host = requiredHost(uri),
            port = requiredPort(uri),
            credentials = EndpointCredentials(password = decode(uri.rawUserInfo.orEmpty())),
            transport = TransportSettings(
                network = "hysteria",
                security = "tls",
                serverName = query["sni"] ?: query["peer"],
                alpn = query["alpn"]?.split(',')?.filter(String::isNotBlank) ?: listOf("h3"),
                fingerprint = query["fp"],
                allowInsecure = query.boolean("insecure"),
                obfs = obfs,
                obfsPassword = obfsPassword,
            ),
        ).also { require(!it.credentials.password.isNullOrEmpty()) { "Hysteria2 password is missing" } }
    }

    private fun parseVmess(value: String): EndpointDefinition {
        val encoded = value.removePrefix("vmess://").substringBefore('#')
        val decoded = decodeBase64(encoded).toString(StandardCharsets.UTF_8).trim()
        require(decoded.startsWith("{")) { "Unsupported VMess URI form" }
        val root = json.parseToJsonElement(decoded).jsonObject
        fun string(name: String) = root[name]?.jsonPrimitive?.contentOrNull
        val host = string("add") ?: throw IllegalArgumentException("VMess host is missing")
        val port = string("port")?.toIntOrNull() ?: root["port"]?.jsonPrimitive?.intOrNull
            ?: throw IllegalArgumentException("VMess port is missing")
        val id = string("id") ?: throw IllegalArgumentException("VMess id is missing")
        runCatching { UUID.fromString(id) }.getOrElse {
            throw IllegalArgumentException("Invalid VMess UUID")
        }
        val queryLike = mapOf(
            "type" to (string("net") ?: "tcp"),
            "security" to (string("tls") ?: "none"),
            "sni" to (string("sni") ?: ""),
            "host" to (string("host") ?: ""),
            "path" to (string("path") ?: ""),
            "serviceName" to (string("path") ?: ""),
            "fp" to (string("fp") ?: ""),
            "pbk" to (string("pbk") ?: ""),
            "sid" to (string("sid") ?: ""),
        ).filterValues(String::isNotEmpty)
        return EndpointDefinition(
            protocol = EndpointProtocol.VMESS,
            name = string("ps")?.takeIf(String::isNotBlank) ?: "VMess $host",
            host = host,
            port = port,
            credentials = EndpointCredentials(
                id = id,
                security = string("scy") ?: "auto",
                alterId = string("aid")?.toIntOrNull() ?: 0,
            ),
            transport = transport(queryLike),
        )
    }

    private fun parseShadowsocks(value: String): EndpointDefinition {
        val raw = value.removePrefix("ss://")
        val fragment = raw.substringAfter('#', "")
        val withoutFragment = raw.substringBefore('#').substringBefore('?')
        val decodedWhole = runCatching {
            decodeBase64(withoutFragment).toString(StandardCharsets.UTF_8)
        }.getOrNull()
        val authority = decodedWhole?.takeIf { '@' in it } ?: withoutFragment
        val credentialsPart = authority.substringBeforeLast('@')
        val serverPart = authority.substringAfterLast('@')
        val decodedCredentials = if (':' in credentialsPart) decode(credentialsPart) else {
            decodeBase64(credentialsPart).toString(StandardCharsets.UTF_8)
        }
        val method = decodedCredentials.substringBefore(':')
        val password = decodedCredentials.substringAfter(':', "")
        require(method.isNotBlank() && password.isNotBlank()) { "Invalid Shadowsocks credentials" }
        val hostPort = parseHostPort(serverPart)
        return EndpointDefinition(
            protocol = EndpointProtocol.SHADOWSOCKS,
            name = decode(fragment).ifBlank { "Shadowsocks ${hostPort.first}" },
            host = hostPort.first,
            port = hostPort.second,
            credentials = EndpointCredentials(method = method, password = password),
        )
    }

    private fun transport(
        query: Map<String, String>,
        defaultSecurity: String = "none",
    ): TransportSettings {
        val network = when (val value = (query["type"] ?: query["network"] ?: "tcp").lowercase()) {
            "tcp", "raw" -> "tcp"
            "ws", "websocket" -> "ws"
            "grpc" -> "grpc"
            "httpupgrade" -> "httpupgrade"
            "xhttp", "splithttp" -> "xhttp"
            else -> throw IllegalArgumentException("Unsupported transport: $value")
        }
        val security = query["security"]?.takeIf { it.isNotBlank() }?.lowercase() ?: defaultSecurity
        require(security in setOf("none", "tls", "reality")) { "Unsupported transport security: $security" }
        require(security != "reality" || network in setOf("tcp", "grpc", "xhttp")) {
            "REALITY is not supported with $network transport"
        }
        val headerType = query["headerType"]?.takeIf(String::isNotBlank)?.lowercase()
        require(headerType == null || headerType in setOf("none", "http")) { "Unsupported TCP header type: $headerType" }
        require(headerType == null || headerType == "none" || network == "tcp") { "TCP header type is only valid with TCP transport" }
        val mode = query["mode"]?.takeIf(String::isNotBlank)
        require(mode == null || network == "xhttp") { "XHTTP mode is only valid with XHTTP transport" }
        require(mode == null || mode in setOf("auto", "packet-up", "stream-up", "stream-one")) { "Unsupported XHTTP mode: $mode" }
        val xhttpExtra = query["extra"]?.takeIf(String::isNotBlank)?.let { value ->
            require(network == "xhttp") { "XHTTP extra is only valid with XHTTP transport" }
            runCatching { json.parseToJsonElement(value).jsonObject }.getOrElse {
                throw IllegalArgumentException("Invalid XHTTP extra settings", it)
            }
        }
        val path = query["path"]
        return TransportSettings(
            network = network,
            security = security,
            serverName = query["sni"] ?: query["serverName"],
            alpn = query["alpn"]?.split(',')?.filter(String::isNotBlank).orEmpty(),
            fingerprint = query["fp"],
            allowInsecure = query.boolean("allowInsecure") || query.boolean("insecure"),
            path = path,
            hostHeader = query["host"],
            serviceName = query["serviceName"] ?: path?.removePrefix("/")?.takeIf { network == "grpc" && it.isNotBlank() },
            headerType = headerType,
            mode = mode,
            xhttpExtra = xhttpExtra,
            realityPublicKey = query["pbk"] ?: query["publicKey"],
            realityShortId = query["sid"] ?: query["shortId"],
            realitySpiderX = query["spx"] ?: query["spiderX"],
        )
    }

    private fun query(uri: URI): Map<String, String> =
        uri.rawQuery.orEmpty().split('&')
            .filter(String::isNotBlank)
            .associate { entry ->
                decode(entry.substringBefore('=')) to decode(entry.substringAfter('=', ""))
            }

    private fun Map<String, String>.boolean(key: String): Boolean =
        this[key]?.lowercase() in setOf("1", "true", "yes")

    private fun strictUri(value: String): URI = runCatching { URI(value) }
        .recoverCatching {
            val fragmentIndex = value.indexOf('#')
            require(fragmentIndex >= 0)
            URI(
                value.substring(0, fragmentIndex + 1) +
                    encodeRawFragment(value.substring(fragmentIndex + 1)),
            )
        }
        .getOrElse { throw IllegalArgumentException("Malformed configuration URI", it) }

    private fun encodeRawFragment(value: String): String = buildString {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        var index = 0
        while (index < bytes.size) {
            val byte = bytes[index].toInt() and 0xff
            val character = byte.toChar()
            if (character == '%' && index + 2 < bytes.size &&
                bytes[index + 1].toInt().toChar().isHexDigit() &&
                bytes[index + 2].toInt().toChar().isHexDigit()
            ) {
                append(character)
                append(bytes[index + 1].toInt().toChar())
                append(bytes[index + 2].toInt().toChar())
                index += 3
            } else {
                if (byte < 0x80 && character in FRAGMENT_SAFE_CHARS) {
                    append(character)
                } else {
                    append('%')
                    append(HEX_DIGITS[byte ushr 4])
                    append(HEX_DIGITS[byte and 0x0f])
                }
                index++
            }
        }
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun requiredHost(uri: URI): String =
        uri.host ?: throw IllegalArgumentException("Server host is missing")
    private fun requiredPort(uri: URI): Int = uri.port.takeIf { it in 1..65_535 }
        ?: throw IllegalArgumentException("Server port is missing")

    private fun fragmentName(uri: URI, fallback: String): String =
        decode(uri.rawFragment.orEmpty()).ifBlank { fallback }

    private fun parseHostPort(value: String): Pair<String, Int> {
        val uri = strictUri("dummy://$value")
        return requiredHost(uri) to requiredPort(uri)
    }

    private fun formatEndpoint(host: String, port: Int): String =
        if (':' in host && !host.startsWith("[")) "[$host]:$port" else "$host:$port"

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private fun decodeBase64(value: String): ByteArray {
        require(value.length <= MAX_BASE64_CHARS) { "Encoded configuration is too large" }
        val normalized = value.trim().replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return runCatching { Base64.getDecoder().decode(padded) }.getOrElse {
            throw IllegalArgumentException("Invalid Base64 configuration", it)
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun canonicalize(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(
            value.entries.sortedBy(Map.Entry<String, JsonElement>::key)
                .associate { (key, child) -> key to canonicalize(child) },
        )
        is JsonArray -> JsonArray(value.map(::canonicalize))
        else -> value
    }

    companion object {
        const val MAX_IMPORT_BYTES = 2 * 1024 * 1024
        const val MAX_BASE64_CHARS = 3 * 1024 * 1024
        private const val FRAGMENT_SAFE_CHARS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~!$&'()*+,;=:@/?"
        private const val HEX_DIGITS = "0123456789ABCDEF"
    }
}

class XrayEndpointRenderer(
    private val json: Json = Json { explicitNulls = false },
) {
    fun render(endpoint: EndpointDefinition): String {
        val outbound = buildJsonObject {
            put("tag", "proxy")
            put("protocol", endpoint.protocol.xrayName())
            put("settings", settings(endpoint))
            streamSettings(endpoint)?.let { put("streamSettings", it) }
        }
        val root = buildJsonObject {
            put("log", buildJsonObject { put("loglevel", "warning") })
            put("inbounds", buildJsonArray { })
            put("outbounds", buildJsonArray {
                add(outbound)
                add(buildJsonObject {
                    put("tag", "direct")
                    put("protocol", "freedom")
                })
                add(buildJsonObject {
                    put("tag", "block")
                    put("protocol", "blackhole")
                })
            })
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    private fun settings(endpoint: EndpointDefinition): JsonObject = when (endpoint.protocol) {
        EndpointProtocol.VLESS,
        EndpointProtocol.VMESS,
        -> buildJsonObject {
            put("vnext", buildJsonArray {
                add(buildJsonObject {
                    put("address", endpoint.host)
                    put("port", endpoint.port)
                    put("users", buildJsonArray {
                        add(buildJsonObject {
                            put("id", requireNotNull(endpoint.credentials.id))
                            if (endpoint.protocol == EndpointProtocol.VLESS) {
                                put("encryption", endpoint.credentials.security ?: "none")
                                endpoint.credentials.flow?.takeIf(String::isNotBlank)?.let { put("flow", it) }
                            } else {
                                put("security", endpoint.credentials.security ?: "auto")
                                put("alterId", endpoint.credentials.alterId)
                            }
                        })
                    })
                })
            })
        }
        EndpointProtocol.HYSTERIA2,
        -> buildJsonObject {
            put("version", 2)
            put("address", endpoint.host)
            put("port", endpoint.port)
        }
        EndpointProtocol.TROJAN,
        EndpointProtocol.SHADOWSOCKS,
        EndpointProtocol.SOCKS,
        EndpointProtocol.HTTP,
        -> buildJsonObject {
            put("servers", buildJsonArray {
                add(buildJsonObject {
                    put("address", endpoint.host)
                    put("port", endpoint.port)
                    endpoint.credentials.password?.let { put("password", it) }
                    endpoint.credentials.method?.let { put("method", it) }
                    if (endpoint.credentials.username != null) {
                        put("users", buildJsonArray {
                            add(buildJsonObject {
                                put("user", endpoint.credentials.username)
                                put("pass", endpoint.credentials.password.orEmpty())
                            })
                        })
                    }
                    endpoint.transport.obfs?.let { put("obfs", it) }
                    endpoint.transport.obfsPassword?.let { put("obfs-password", it) }
                })
            })
        }
    }

    private fun streamSettings(endpoint: EndpointDefinition): JsonObject? {
        val transport = endpoint.transport
        if (transport.network == "tcp" && transport.security == "none" &&
            transport.headerType in setOf(null, "none")
        ) return null
        return buildJsonObject {
            put("network", transport.network)
            put("security", transport.security)
            when (transport.network) {
                "tcp" -> transport.headerType?.takeIf { it != "none" }?.let { headerType ->
                    put("tcpSettings", buildJsonObject {
                        put("header", buildJsonObject {
                            put("type", headerType)
                            if (headerType == "http") {
                                put("request", buildJsonObject {
                                    transport.path?.let { path -> put("path", buildJsonArray { add(JsonPrimitive(path)) }) }
                                    transport.hostHeader?.let { host -> put("headers", buildJsonObject { put("Host", buildJsonArray { add(JsonPrimitive(host)) }) }) }
                                })
                            }
                        })
                    })
                }
                "ws" -> put("wsSettings", buildJsonObject {
                    transport.path?.let { put("path", it) }
                    transport.hostHeader?.let {
                        put("headers", buildJsonObject { put("Host", it) })
                    }
                })
                "grpc" -> put("grpcSettings", buildJsonObject {
                    transport.serviceName?.let { put("serviceName", it) }
                })
                "httpupgrade" -> put("httpupgradeSettings", buildJsonObject {
                    transport.path?.let { put("path", it) }
                    transport.hostHeader?.let { put("host", it) }
                })
                "xhttp" -> put("xhttpSettings", buildJsonObject {
                    transport.path?.let { put("path", it) }
                    transport.hostHeader?.let { put("host", it) }
                    transport.mode?.let { put("mode", it) }
                    transport.xhttpExtra?.let { put("extra", it) }
                })
                "hysteria" -> put("hysteriaSettings", buildJsonObject {
                    put("version", 2)
                    put("auth", endpoint.credentials.password.orEmpty())
                })
            }
            if (transport.network == "hysteria" && transport.obfs == "salamander") {
                put("finalmask", buildJsonObject {
                    put("udp", buildJsonArray { add(buildJsonObject { put("type", "salamander"); put("settings", buildJsonObject { put("password", transport.obfsPassword.orEmpty()) }) }) })
                })
            }
            when (transport.security) {
                "tls" -> put("tlsSettings", buildJsonObject {
                    transport.serverName?.let { put("serverName", it) }
                    if (transport.alpn.isNotEmpty()) {
                        put("alpn", buildJsonArray { transport.alpn.forEach { add(JsonPrimitive(it)) } })
                    }
                    transport.fingerprint?.let { put("fingerprint", it) }
                    put("allowInsecure", transport.allowInsecure)
                })
                "reality" -> put("realitySettings", buildJsonObject {
                    transport.serverName?.let { put("serverName", it) }
                    transport.realityPublicKey?.let { put("publicKey", it) }
                    transport.realityShortId?.let { put("shortId", it) }
                    transport.fingerprint?.let { put("fingerprint", it) }
                    transport.realitySpiderX?.let { put("spiderX", it) }
                })
            }
        }
    }

    private fun EndpointProtocol.xrayName(): String = when (this) {
        EndpointProtocol.SHADOWSOCKS -> "shadowsocks"
        EndpointProtocol.HYSTERIA2 -> "hysteria"
        else -> name.lowercase()
    }
}