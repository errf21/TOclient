package com.toclient.core.subscription

import com.toclient.core.contract.ProfileRepository
import com.toclient.core.model.ProfileId
import com.toclient.core.model.ProfileSource
import com.toclient.core.model.SensitiveProfileConfig
import com.toclient.core.model.StoredProfile
import com.toclient.core.model.VpnProfile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

class SubscriptionUpdater(
    private val subscriptions: SubscriptionRepository,
    private val profiles: ProfileRepository,
    private val fetcher: SubscriptionFetcher,
    private val parser: SubscriptionParser = SubscriptionParser(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun update(id: SubscriptionId): SubscriptionUpdateResult {
        val subscription = subscriptions.get(id) ?: error("Subscription not found")
        require(subscription.enabled) { "Subscription is disabled" }
        val now = nowMillis()
        val url = subscriptions.getUrl(subscription)
        validateUrl(url)

        return try {
            val response = fetcher.fetch(
                SubscriptionFetchRequest(url, subscription.etag, subscription.lastModified),
            )
            if (response.status == FetchStatus.NOT_MODIFIED) {
                subscriptions.upsert(
                    subscription.copy(lastAttemptMillis = now, lastError = null),
                    url = null,
                )
                SubscriptionUpdateResult(id, changed = false, importedProfiles = emptyList())
            } else {
                val contentHash = sha256(response.body)
                if (contentHash == subscription.contentHash) {
                    subscriptions.upsert(
                        subscription.copy(
                            lastAttemptMillis = now,
                            lastSuccessMillis = now,
                            etag = response.etag ?: subscription.etag,
                            lastModified = response.lastModified ?: subscription.lastModified,
                            lastError = null,
                            uploadedBytes = response.uploadedBytes ?: subscription.uploadedBytes,
                            downloadedBytes = response.downloadedBytes ?: subscription.downloadedBytes,
                            totalBytes = response.totalBytes ?: subscription.totalBytes,
                            expiresAtMillis = response.expiresAtMillis ?: subscription.expiresAtMillis,
                        ),
                        url = null,
                    )
                    return SubscriptionUpdateResult(id, false, emptyList())
                }
                val imported = parser.parse(response.body)
                val stored = imported.map { item ->
                    val stable = sha256("${id.value}:${item.fingerprint}".toByteArray())
                    val profileId = ProfileId("sub-${stable.take(32)}")
                    val existing = profiles.get(profileId)
                    StoredProfile(
                        profile = VpnProfile(
                            id = profileId,
                            name = item.name.take(120),
                            secretRef = "cfg-$stable",
                            source = ProfileSource.Subscription(id.value),
                            fingerprint = item.fingerprint,
                            protocolLabel = item.protocol,
                            endpointLabel = item.endpoint,
                            transportLabel = item.transport,
                            securityLabel = item.security,
                        ).let { refreshed ->
                            existing?.let {
                                refreshed.copy(
                                    dns = it.dns,
                                    routing = it.routing,
                                    perApp = it.perApp,
                                    mtu = it.mtu,
                                    ipv6Enabled = it.ipv6Enabled,
                                    reconnect = it.reconnect,
                                    enabled = it.enabled,
                                    isFavorite = it.isFavorite,
                                    latencyMillis = it.latencyMillis,
                                    lastTestedMillis = it.lastTestedMillis,
                                    latencyStatus = it.latencyStatus,
                                    latencyError = it.latencyError,
                                )
                            } ?: refreshed
                        },
                        sensitive = SensitiveProfileConfig(item.xrayJson),
                    )
                }
                profiles.replaceSubscriptionProfiles(id.value, stored)
                subscriptions.upsert(
                    subscription.copy(
                        lastAttemptMillis = now,
                        lastSuccessMillis = now,
                        etag = response.etag,
                        lastModified = response.lastModified,
                        contentHash = contentHash,
                        profileCount = stored.size,
                        lastError = null,
                        uploadedBytes = response.uploadedBytes,
                        downloadedBytes = response.downloadedBytes,
                        totalBytes = response.totalBytes,
                        expiresAtMillis = response.expiresAtMillis,
                    ),
                    url = null,
                )
                SubscriptionUpdateResult(id, true, stored.map { it.profile.id })
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            subscriptions.upsert(
                subscription.copy(
                    lastAttemptMillis = now,
                    lastError = (error.message ?: error::class.simpleName.orEmpty()).take(300),
                ),
                url = null,
            )
            throw error
        }
    }

    suspend fun updateDue(): List<Result<SubscriptionUpdateResult>> {
        val now = nowMillis()
        return subscriptions.list().filter { it.isDue(now) }.map {
            try {
                Result.success(update(it.id))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Result.failure(error)
            }
        }
    }

    private fun validateUrl(url: String) {
        val uri = java.net.URI(url)
        require(uri.scheme == "https") { "Subscription URL must use HTTPS" }
        require(!uri.host.isNullOrBlank()) { "Subscription URL has no host" }
        require(uri.userInfo == null) { "Credentials in subscription URL authority are not allowed" }
    }

    private fun sha256(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value)
            .joinToString("") { "%02x".format(it) }

    private fun sha256(value: String): String = sha256(value.toByteArray(StandardCharsets.UTF_8))
}