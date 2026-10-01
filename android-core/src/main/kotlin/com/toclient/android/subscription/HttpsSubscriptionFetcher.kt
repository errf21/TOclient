package com.toclient.android.subscription

import com.toclient.core.subscription.FetchStatus
import com.toclient.core.subscription.SubscriptionFetchRequest
import com.toclient.core.subscription.SubscriptionFetchResponse
import com.toclient.core.subscription.SubscriptionFetcher
import com.toclient.core.subscription.SubscriptionParser
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HttpsSubscriptionFetcher : SubscriptionFetcher {
    override suspend fun fetch(request: SubscriptionFetchRequest): SubscriptionFetchResponse =
        withContext(Dispatchers.IO) {
            var current = URI(request.url)
            repeat(MAX_REDIRECTS + 1) { redirectCount ->
                require(current.scheme == "https") { "Subscription redirect must use HTTPS" }
                require(!current.host.isNullOrBlank()) { "Subscription redirect has no host" }
                require(current.userInfo == null) {
                    "Credentials in subscription URL authority are not allowed"
                }
                val connection = URL(current.toASCIIString()).openConnection() as HttpsURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                connection.readTimeout = READ_TIMEOUT_MILLIS
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "text/plain, application/json")
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("User-Agent", "TO-Client/0.1")
                request.etag?.let { connection.setRequestProperty("If-None-Match", it) }
                request.lastModified?.let {
                    connection.setRequestProperty("If-Modified-Since", it)
                }
                try {
                    when (val code = connection.responseCode) {
                        HttpURLConnection.HTTP_NOT_MODIFIED ->
                            return@withContext SubscriptionFetchResponse(FetchStatus.NOT_MODIFIED)

                        in 300..399 -> {
                            require(redirectCount < MAX_REDIRECTS) { "Too many redirects" }
                            val location = connection.getHeaderField("Location")
                                ?: error("Redirect has no Location")
                            current = current.resolve(location)
                        }

                        HttpURLConnection.HTTP_OK -> {
                            val declared = connection.contentLengthLong
                            require(declared <= SubscriptionParser.MAX_SUBSCRIPTION_BYTES) {
                                "Subscription is too large"
                            }
                            val body = readBounded(connection)
                            val userInfo = parseUserInfo(connection)
                            return@withContext SubscriptionFetchResponse(
                                status = FetchStatus.UPDATED,
                                body = body,
                                etag = connection.getHeaderField("ETag"),
                                lastModified = connection.getHeaderField("Last-Modified"),
                                uploadedBytes = userInfo["upload"],
                                downloadedBytes = userInfo["download"],
                                totalBytes = userInfo["total"],
                                expiresAtMillis = userInfo["expire"]
                                    ?.takeIf { it > 0 }
                                    ?.times(1_000),
                            )
                        }

                        else -> error("Subscription HTTP status $code")
                    }
                } finally {
                    connection.disconnect()
                }
            }
            error("Subscription redirect failed")
        }

    private fun readBounded(connection: HttpsURLConnection): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        connection.inputStream.use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= SubscriptionParser.MAX_SUBSCRIPTION_BYTES) {
                    "Subscription is too large"
                }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun parseUserInfo(connection: HttpsURLConnection): Map<String, Long> =
        connection.getHeaderField("subscription-userinfo")
            .orEmpty()
            .split(';')
            .mapNotNull { field ->
                val parts = field.trim().split('=', limit = 2)
                if (parts.size != 2) null
                else parts[1].trim().toLongOrNull()?.takeIf { it >= 0 }?.let {
                    parts[0].trim().lowercase() to it
                }
            }
            .toMap()

    companion object {
        private const val MAX_REDIRECTS = 3
        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val READ_TIMEOUT_MILLIS = 30_000
    }
}