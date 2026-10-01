package com.toclient.android.subscription

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.toclient.android.persistence.KeystoreSecretStore
import com.toclient.core.subscription.Subscription
import com.toclient.core.subscription.SubscriptionId
import com.toclient.core.subscription.SubscriptionRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.subscriptionDataStore by preferencesDataStore(name = "to_client_subscriptions")

class AndroidSubscriptionRepository(
    context: Context,
    private val secrets: KeystoreSecretStore = KeystoreSecretStore(context),
    private val json: Json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    },
) : SubscriptionRepository {
    private val appContext = context.applicationContext
    private val mutex = Mutex()

    override suspend fun upsert(subscription: Subscription, url: String?) {
        mutex.withLock {
            if (url != null) {
                require(url.toByteArray().size <= MAX_URL_BYTES)
                secrets.write(subscription.urlSecretRef, url.toByteArray(Charsets.UTF_8))
            }
            val values = read().associateBy { it.id }.toMutableMap()
            values[subscription.id] = subscription
            write(values.values.sortedBy { it.name.lowercase() })
        }
    }

    override suspend fun get(id: SubscriptionId): Subscription? =
        read().firstOrNull { it.id == id }

    override suspend fun getUrl(subscription: Subscription): String =
        secrets.read(subscription.urlSecretRef).toString(Charsets.UTF_8)

    override suspend fun list(): List<Subscription> = read()

    override suspend fun delete(id: SubscriptionId) {
        mutex.withLock {
            val current = read()
            val removed = current.firstOrNull { it.id == id } ?: return
            write(current.filterNot { it.id == id })
            secrets.delete(removed.urlSecretRef)
        }
    }

    private suspend fun read(): List<Subscription> {
        val encoded = appContext.subscriptionDataStore.data.first()[SUBSCRIPTIONS]
            ?: return emptyList()
        return json.decodeFromString(ListSerializer(Subscription.serializer()), encoded)
    }

    private suspend fun write(values: List<Subscription>) {
        val encoded = json.encodeToString(ListSerializer(Subscription.serializer()), values)
        appContext.subscriptionDataStore.edit { it[SUBSCRIPTIONS] = encoded }
    }

    companion object {
        private const val MAX_URL_BYTES = 8 * 1024
        private val SUBSCRIPTIONS = stringPreferencesKey("subscriptions.v1")
    }
}