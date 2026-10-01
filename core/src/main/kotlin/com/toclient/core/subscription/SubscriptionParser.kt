package com.toclient.core.subscription

import com.toclient.core.importer.ConfigImporter
import com.toclient.core.importer.ImportedConfig
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

class SubscriptionParser(
    private val importer: ConfigImporter = ConfigImporter(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun parse(body: ByteArray): List<ImportedConfig> {
        require(body.size <= MAX_SUBSCRIPTION_BYTES) { "Subscription is too large" }
        val text = body.toString(StandardCharsets.UTF_8).removePrefix("\uFEFF").trim()
        require(text.isNotEmpty()) { "Subscription is empty" }
        val candidates = extractCandidates(text)
        require(candidates.size <= MAX_PROFILES) { "Subscription has too many profiles" }
        val imported = candidates.mapNotNull { candidate ->
            runCatching { importer.import(candidate) }.getOrNull()
        }.distinctBy(ImportedConfig::fingerprint)
        require(imported.isNotEmpty()) { "Subscription contains no supported profiles" }
        return imported
    }

    private fun extractCandidates(text: String): List<String> {
        if (text.startsWith("{")) return listOf(text)
        if (text.startsWith("[")) {
            return json.parseToJsonElement(text).jsonArray.mapNotNull {
                it.jsonPrimitive.contentOrNull
            }
        }
        val direct = lines(text)
        if (direct.any(::looksLikeConfig)) return direct.filter(::looksLikeConfig)

        val decoded = decodeBase64(text)
        return lines(decoded).filter(::looksLikeConfig)
    }

    private fun lines(value: String): List<String> =
        value.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()

    private fun looksLikeConfig(value: String): Boolean =
        value.startsWith("{") || SUPPORTED_SCHEMES.any { value.startsWith("$it://", true) }

    private fun decodeBase64(value: String): String {
        val compact = value.filterNot(Char::isWhitespace)
        require(compact.length <= MAX_BASE64_CHARS)
        val normalized = compact.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return runCatching {
            Base64.getDecoder().decode(padded).toString(StandardCharsets.UTF_8)
        }.getOrElse { throw IllegalArgumentException("Unsupported subscription encoding", it) }
    }

    companion object {
        const val MAX_SUBSCRIPTION_BYTES = 4 * 1024 * 1024
        const val MAX_BASE64_CHARS = 6 * 1024 * 1024
        const val MAX_PROFILES = 2_000
        private val SUPPORTED_SCHEMES = setOf(
            "vless", "vmess", "trojan", "ss", "socks", "socks5", "http", "https",
            "hysteria2", "hy2",
        )
    }
}