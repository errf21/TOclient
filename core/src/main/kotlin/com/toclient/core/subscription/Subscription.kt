package com.toclient.core.subscription

import com.toclient.core.model.ProfileId
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class SubscriptionId(val value: String) {
    init {
        require(value.matches(Regex("[A-Za-z0-9._-]{1,96}")))
    }
}

@Serializable
data class Subscription(
    val id: SubscriptionId,
    val name: String,
    val urlSecretRef: String,
    val enabled: Boolean = true,
    val updateIntervalHours: Int = 24,
    val lastSuccessMillis: Long? = null,
    val lastAttemptMillis: Long? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val contentHash: String? = null,
    val profileCount: Int = 0,
    val lastError: String? = null,
    val sourceHost: String? = null,
    val uploadedBytes: Long? = null,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val expiresAtMillis: Long? = null,
) {
    init {
        require(name.isNotBlank())
        require(urlSecretRef.isNotBlank())
        require(updateIntervalHours in 1..168)
        require(profileCount >= 0)
        require(uploadedBytes == null || uploadedBytes >= 0)
        require(downloadedBytes == null || downloadedBytes >= 0)
        require(totalBytes == null || totalBytes >= 0)
    }

    fun isDue(nowMillis: Long): Boolean =
        enabled && (lastAttemptMillis == null ||
            nowMillis - lastAttemptMillis >= updateIntervalHours * 60L * 60L * 1_000L)
}

interface SubscriptionRepository {
    suspend fun upsert(subscription: Subscription, url: String?)
    suspend fun get(id: SubscriptionId): Subscription?
    suspend fun getUrl(subscription: Subscription): String
    suspend fun list(): List<Subscription>
    suspend fun delete(id: SubscriptionId)
}

data class SubscriptionFetchRequest(
    val url: String,
    val etag: String?,
    val lastModified: String?,
)

data class SubscriptionFetchResponse(
    val status: FetchStatus,
    val body: ByteArray = byteArrayOf(),
    val etag: String? = null,
    val lastModified: String? = null,
    val uploadedBytes: Long? = null,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val expiresAtMillis: Long? = null,
)

enum class FetchStatus { UPDATED, NOT_MODIFIED }

fun interface SubscriptionFetcher {
    suspend fun fetch(request: SubscriptionFetchRequest): SubscriptionFetchResponse
}

data class SubscriptionUpdateResult(
    val subscriptionId: SubscriptionId,
    val changed: Boolean,
    val importedProfiles: List<ProfileId>,
)