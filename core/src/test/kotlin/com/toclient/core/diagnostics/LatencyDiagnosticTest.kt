package com.toclient.core.diagnostics

import com.toclient.core.contract.EngineAdapter
import com.toclient.core.contract.EngineStartRequest
import com.toclient.core.model.TaggedTrafficCounter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

class LatencyDiagnosticTest {
    @Test
    fun `returns real engine delay only while connected`() = runTest {
        val engine = FakeEngine(running = true, result = { 42L })
        val success = assertIs<LatencyResult.Success>(
            LatencyDiagnostic(engine).probe("https://connectivity.example/check"),
        )
        assertEquals(42L, success.latencyMillis)

        engine.running = false
        val stopped = assertIs<LatencyResult.Failure>(
            LatencyDiagnostic(engine).probe("https://connectivity.example/check"),
        )
        assertEquals(DiagnosticFailure.NOT_CONNECTED, stopped.category)
    }

    @Test
    fun `validates url and classifies engine failures`() = runTest {
        val invalid = assertIs<LatencyResult.Failure>(
            LatencyDiagnostic(FakeEngine()).probe("http://example.com"),
        )
        assertEquals(DiagnosticFailure.INVALID_RESPONSE, invalid.category)

        val failures = mapOf(
            "DNS resolve failed" to DiagnosticFailure.DNS,
            "request timeout" to DiagnosticFailure.TIMEOUT,
            "TLS certificate failure" to DiagnosticFailure.TLS,
            "network unreachable" to DiagnosticFailure.NETWORK,
            "unexpected" to DiagnosticFailure.UNKNOWN,
        )
        failures.forEach { (message, category) ->
            val result = assertIs<LatencyResult.Failure>(
                LatencyDiagnostic(FakeEngine(result = { error(message) }))
                    .probe("https://example.com/check"),
            )
            assertEquals(category, result.category)
        }
    }

    private class FakeEngine(
        var running: Boolean = true,
        private val result: () -> Long = { 0L },
    ) : EngineAdapter {
        override val id = "fake"
        override suspend fun start(request: EngineStartRequest) = Unit
        override suspend fun stop() = Unit
        override suspend fun isRunning() = running
        override suspend fun traffic(): List<TaggedTrafficCounter> = emptyList()
        override suspend fun measureDelay(url: String) = result()
    }
}