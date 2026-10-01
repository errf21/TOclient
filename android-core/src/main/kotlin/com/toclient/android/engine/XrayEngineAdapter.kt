package com.toclient.android.engine

import android.content.Context
import android.util.Log
import com.toclient.core.contract.EngineAdapter
import com.toclient.core.contract.EngineStartRequest
import com.toclient.core.model.TaggedTrafficCounter
import com.toclient.core.model.TrafficCounter
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

class XrayEngineAdapter(
    context: Context,
) : EngineAdapter {
    override val id: String = "xray-androidlibxraylite-26.9.9"
    private val lock = Any()
    private var controller: CoreController? = null

    init {
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        Libv2ray.initCoreEnv(
            context.noBackupFilesDir.absolutePath,
            Base64.getUrlEncoder().withoutPadding().encodeToString(key),
        )
    }

    override suspend fun start(request: EngineStartRequest) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            check(controller?.isRunning != true) { "Xray is already running" }
            val newController = Libv2ray.newCoreController(Callback)
            try {
                // External HevTun owns the Android TUN descriptor; zero disables Xray TUN mode.
                newController.startLoop(request.xrayJson, 0)
                check(newController.isRunning) { "Xray did not enter running state" }
                controller = newController
            } catch (error: Throwable) {
                runCatching { newController.stopLoop() }
                throw error
            }
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            controller?.let { runCatching { it.stopLoop() }.getOrThrow() }
            controller = null
        }
    }

    override suspend fun isRunning(): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) { controller?.isRunning == true }
    }

    override suspend fun traffic(): List<TaggedTrafficCounter> = withContext(Dispatchers.IO) {
        val raw = synchronized(lock) {
            controller?.takeIf { it.isRunning }?.queryAllOutboundTrafficStats().orEmpty()
        }
        XrayTrafficParser.parse(raw)
    }

    override suspend fun measureDelay(url: String): Long = withContext(Dispatchers.IO) {
        synchronized(lock) {
            controller?.takeIf { it.isRunning }?.measureDelay(url)
                ?: error("Xray is not running")
        }
    }

    fun version(): String = Libv2ray.checkVersionX()

    private object Callback : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(code: Long, status: String?): Long {
            Log.i("TOClient.Xray", "core[$code]: ${status.orEmpty()}")
            return 0
        }
    }
}

object XrayTrafficParser {
    fun parse(raw: String): List<TaggedTrafficCounter> {
        val totals = linkedMapOf<String, LongArray>()
        raw.split(';').forEach { entry ->
            val parts = entry.split(',')
            if (parts.size != 3) return@forEach
            val tag = parts[0].trim()
            val direction = parts[1].trim().lowercase()
            val value = parts[2].trim().toLongOrNull()?.coerceAtLeast(0) ?: return@forEach
            if (tag.isEmpty()) return@forEach
            val counter = totals.getOrPut(tag) { longArrayOf(0, 0) }
            when (direction) {
                "uplink", "upload" -> counter[0] += value
                "downlink", "download" -> counter[1] += value
            }
        }
        return totals.map { (tag, values) ->
            TaggedTrafficCounter(tag, TrafficCounter(values[0], values[1]))
        }
    }
}