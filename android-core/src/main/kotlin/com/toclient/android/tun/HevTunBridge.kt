package com.toclient.android.tun

import android.content.Context
import com.toclient.core.contract.TunBridge
import com.toclient.core.contract.TunBridgeStartRequest
import com.toclient.core.model.TrafficCounter
import hev.htproxy.TProxyService
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HevTunBridge(
    context: Context,
) : TunBridge {
    override val id: String = "hev-socks5-tunnel-2.18.0"
    private val configFile = File(context.noBackupFilesDir, "hev-tun.yml")

    override suspend fun start(request: TunBridgeStartRequest) = withContext(Dispatchers.IO) {
        check(!TProxyService.TProxyIsRunning()) { "HevTun is already running" }
        configFile.writeText(renderConfig(request))
        check(TProxyService.TProxyStartService(configFile.absolutePath, request.tunFileDescriptor)) {
            "HevTun failed to start"
        }
        check(TProxyService.TProxyIsRunning()) { "HevTun did not enter running state" }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        if (TProxyService.TProxyIsRunning()) {
            check(TProxyService.TProxyStopService()) { "HevTun failed to stop" }
        }
    }

    override suspend fun isRunning(): Boolean = withContext(Dispatchers.IO) {
        TProxyService.TProxyIsRunning()
    }

    override suspend fun traffic(): TrafficCounter = withContext(Dispatchers.IO) {
        if (!TProxyService.TProxyIsRunning()) return@withContext TrafficCounter()
        val values = TProxyService.TProxyGetStats()
        // Native order: tx_packets, tx_bytes, rx_packets, rx_bytes.
        TrafficCounter(
            uploadedBytes = values.getOrElse(1) { 0 }.coerceAtLeast(0),
            downloadedBytes = values.getOrElse(3) { 0 }.coerceAtLeast(0),
        )
    }

    internal fun renderConfig(request: TunBridgeStartRequest): String = buildString {
        appendLine("tunnel:")
        appendLine("  name: tun0")
        appendLine("  mtu: ${request.mtu}")
        appendLine("  multi-queue: false")
        appendLine("  ipv4: '${request.ipv4Address}'")
        request.ipv6Address?.let { appendLine("  ipv6: '$it'") }
        appendLine("  icmp: 'off'")
        appendLine("socks5:")
        appendLine("  port: ${request.socksEndpoint.port}")
        appendLine("  address: '${request.socksEndpoint.host}'")
        appendLine("  udp: 'udp'")
        appendLine("misc:")
        appendLine("  log-file: null")
        appendLine("  log-level: warn")
        appendLine("  connect-timeout: 10000")
        appendLine("  tcp-read-write-timeout: 300000")
        appendLine("  udp-read-write-timeout: 60000")
    }
}