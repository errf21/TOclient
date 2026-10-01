package com.toclient.core.diagnostics

import com.toclient.core.contract.EngineAdapter
import java.net.URI

sealed interface LatencyResult {
    data class Success(val url: String, val latencyMillis: Long) : LatencyResult
    data class Failure(
        val url: String,
        val category: DiagnosticFailure,
        val message: String,
    ) : LatencyResult
}

enum class DiagnosticFailure {
    NOT_CONNECTED,
    DNS,
    TIMEOUT,
    TLS,
    NETWORK,
    INVALID_RESPONSE,
    UNKNOWN,
}

class LatencyDiagnostic(
    private val engine: EngineAdapter,
) {
    suspend fun probe(url: String): LatencyResult {
        val uri = runCatching { URI(url) }.getOrElse {
            return LatencyResult.Failure(url, DiagnosticFailure.INVALID_RESPONSE, "Invalid URL")
        }
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) {
            return LatencyResult.Failure(
                url,
                DiagnosticFailure.INVALID_RESPONSE,
                "Diagnostic URL must use HTTPS",
            )
        }
        if (!engine.isRunning()) {
            return LatencyResult.Failure(url, DiagnosticFailure.NOT_CONNECTED, "VPN is not connected")
        }
        return try {
            val delay = engine.measureDelay(url)
            if (delay < 0) {
                LatencyResult.Failure(
                    url,
                    DiagnosticFailure.INVALID_RESPONSE,
                    "Engine returned an invalid delay",
                )
            } else {
                LatencyResult.Success(url, delay)
            }
        } catch (error: Throwable) {
            val message = error.message.orEmpty()
            LatencyResult.Failure(url, classify(message), message.take(300))
        }
    }

    private fun classify(message: String): DiagnosticFailure {
        val lower = message.lowercase()
        return when {
            "timeout" in lower || "deadline" in lower -> DiagnosticFailure.TIMEOUT
            "dns" in lower || "resolve" in lower || "no such host" in lower ->
                DiagnosticFailure.DNS
            "tls" in lower || "certificate" in lower || "handshake" in lower ->
                DiagnosticFailure.TLS
            "connect" in lower || "network" in lower || "unreachable" in lower ->
                DiagnosticFailure.NETWORK
            else -> DiagnosticFailure.UNKNOWN
        }
    }
}