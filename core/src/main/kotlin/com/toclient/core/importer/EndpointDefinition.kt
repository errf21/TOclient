package com.toclient.core.importer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class EndpointDefinition(
    val protocol: EndpointProtocol,
    val name: String,
    val host: String,
    val port: Int,
    val credentials: EndpointCredentials = EndpointCredentials(),
    val transport: TransportSettings = TransportSettings(),
) {
    init {
        require(name.isNotBlank())
        require(host.isNotBlank())
        require(port in 1..65_535)
    }
}

@Serializable
enum class EndpointProtocol {
    VLESS,
    VMESS,
    TROJAN,
    SHADOWSOCKS,
    SOCKS,
    HTTP,
    HYSTERIA2,
}

@Serializable
data class EndpointCredentials(
    val id: String? = null,
    val username: String? = null,
    val password: String? = null,
    val method: String? = null,
    val security: String? = null,
    val flow: String? = null,
    val alterId: Int = 0,
)

@Serializable
data class TransportSettings(
    val network: String = "tcp",
    val security: String = "none",
    val serverName: String? = null,
    val alpn: List<String> = emptyList(),
    val fingerprint: String? = null,
    val allowInsecure: Boolean = false,
    val path: String? = null,
    val hostHeader: String? = null,
    val serviceName: String? = null,
    val headerType: String? = null,
    val mode: String? = null,
    val xhttpExtra: JsonObject? = null,
    val realityPublicKey: String? = null,
    val realityShortId: String? = null,
    val realitySpiderX: String? = null,
    val obfs: String? = null,
    val obfsPassword: String? = null,
)

data class ImportedConfig(
    val name: String,
    val xrayJson: String,
    val fingerprint: String,
    val protocol: String,
    val endpoint: String? = null,
    val transport: String? = null,
    val security: String? = null,
)