package com.toclient.core.subscription

import com.toclient.core.contract.ProfileRepository
import com.toclient.core.model.DnsPolicy
import com.toclient.core.model.LatencyTestStatus
import com.toclient.core.model.ProfileId
import com.toclient.core.model.ProfileSource
import com.toclient.core.model.SensitiveProfileConfig
import com.toclient.core.model.StoredProfile
import com.toclient.core.model.VpnProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SubscriptionUpdaterTest {
    private val id = SubscriptionId("primary")
    private val now = 123_456_789L

    @Test
    fun `updated response atomically replaces deduplicated profiles`() = runTest {
        val subscriptions = FakeSubscriptions(subscription())
        val profiles = FakeProfiles()
        val body = """
            trojan://secret@one.example:443#One
            trojan://secret@one.example:443#Duplicate
            socks5://user:pass@two.example:1080#Two
        """.trimIndent().toByteArray()
        val updater = SubscriptionUpdater(
            subscriptions,
            profiles,
            SubscriptionFetcher {
                assertEquals("https://subscriptions.example/list", it.url)
                SubscriptionFetchResponse(FetchStatus.UPDATED, body, "etag-1", "date-1")
            },
            nowMillis = { now },
        )

        val result = updater.update(id)

        assertTrue(result.changed)
        assertEquals(2, result.importedProfiles.size)
        assertEquals(2, profiles.replaced.single().size)
        assertTrue(result.importedProfiles.all { it.value.startsWith("sub-") })
        val saved = assertNotNull(subscriptions.value)
        assertEquals(now, saved.lastSuccessMillis)
        assertEquals(2, saved.profileCount)
        assertEquals("etag-1", saved.etag)
        assertEquals(null, saved.lastError)
    }

    @Test
    fun `subscription persistence retains xhttp extra in sensitive runtime json`() = runTest {
        val subscriptions = FakeSubscriptions(subscription())
        val profiles = FakeProfiles()
        val body = (
            "vless://11111111-2222-3333-4444-555555555555@disi.netbazshop.ir:1001" +
                "?encryption=none&security=tls&type=xhttp&headerType=none&path=%2F" +
                "&host=cafeariel.ir&mode=stream-up" +
                "&extra=%7B%22xPaddingBytes%22%3A%22100-1000%22%2C" +
                "%22xPaddingObfsMode%22%3Atrue%7D&sni=sslcert.okamii1.ir#Finland"
        ).toByteArray()
        val updater = SubscriptionUpdater(
            subscriptions,
            profiles,
            SubscriptionFetcher { SubscriptionFetchResponse(FetchStatus.UPDATED, body) },
            nowMillis = { now },
        )

        updater.update(id)

        val sensitive = profiles.replaced.single().single().sensitive.xrayJson
        val extra = Json.parseToJsonElement(sensitive).jsonObject
            .getValue("outbounds").jsonArray.first().jsonObject
            .getValue("streamSettings").jsonObject
            .getValue("xhttpSettings").jsonObject
            .getValue("extra").jsonObject
        assertEquals("100-1000", extra.getValue("xPaddingBytes").jsonPrimitive.content)
        assertEquals("true", extra.getValue("xPaddingObfsMode").jsonPrimitive.content)
    }

    @Test
    fun `refresh preserves ownership favorites latency and user network policy`() = runTest {
        val subscriptions = FakeSubscriptions(subscription())
        val profiles = FakeProfiles()
        val body = "trojan://secret@one.example:443#One".toByteArray()
        val updater = SubscriptionUpdater(
            subscriptions,
            profiles,
            SubscriptionFetcher { SubscriptionFetchResponse(FetchStatus.UPDATED, body) },
            nowMillis = { now },
        )

        updater.update(id)
        val first = profiles.replaced.single().single().profile
        profiles.existing[first.id] = first.copy(
            isFavorite = true,
            latencyMillis = 41,
            lastTestedMillis = 100,
            latencyStatus = LatencyTestStatus.SUCCESS,
            dns = DnsPolicy(listOf("9.9.9.9")),
        )
        profiles.replaced.clear()
        subscriptions.value = subscriptions.value?.copy(contentHash = null)

        updater.update(id)

        val refreshed = profiles.replaced.single().single().profile
        assertEquals(ProfileSource.Subscription(id.value), refreshed.source)
        assertTrue(refreshed.isFavorite)
        assertEquals(41, refreshed.latencyMillis)
        assertEquals(100, refreshed.lastTestedMillis)
        assertEquals(LatencyTestStatus.SUCCESS, refreshed.latencyStatus)
        assertEquals(listOf("9.9.9.9"), refreshed.dns.servers)
    }

    @Test
    fun `not modified response preserves profiles and updates attempt metadata`() = runTest {
        val subscriptions = FakeSubscriptions(subscription())
        val profiles = FakeProfiles()
        val updater = SubscriptionUpdater(
            subscriptions,
            profiles,
            SubscriptionFetcher {
                SubscriptionFetchResponse(FetchStatus.NOT_MODIFIED)
            },
            nowMillis = { now },
        )

        val result = updater.update(id)

        assertEquals(false, result.changed)
        assertTrue(profiles.replaced.isEmpty())
        assertEquals(now, subscriptions.value?.lastAttemptMillis)
    }

    @Test
    fun `failure does not replace profiles and records bounded error`() = runTest {
        val subscriptions = FakeSubscriptions(subscription())
        val profiles = FakeProfiles()
        val updater = SubscriptionUpdater(
            subscriptions,
            profiles,
            SubscriptionFetcher { error("network unavailable") },
            nowMillis = { now },
        )

        assertFailsWith<IllegalStateException> { updater.update(id) }
        assertTrue(profiles.replaced.isEmpty())
        assertEquals("network unavailable", subscriptions.value?.lastError)
    }

    @Test
    fun `rejects non https source before fetch`() = runTest {
        val subscriptions = FakeSubscriptions(
            subscription(),
            url = "http://subscriptions.example/list",
        )
        var fetched = false
        val updater = SubscriptionUpdater(
            subscriptions,
            FakeProfiles(),
            SubscriptionFetcher {
                fetched = true
                SubscriptionFetchResponse(FetchStatus.NOT_MODIFIED)
            },
        )

        assertFailsWith<IllegalArgumentException> { updater.update(id) }
        assertEquals(false, fetched)
    }

    @Test
    fun `cancellation is never converted into subscription failure metadata`() = runTest {
        val subscriptions = FakeSubscriptions(subscription())
        val updater = SubscriptionUpdater(
            subscriptions,
            FakeProfiles(),
            SubscriptionFetcher { throw CancellationException("stopped") },
            nowMillis = { now },
        )

        assertFailsWith<CancellationException> { updater.update(id) }
        assertEquals(null, subscriptions.value?.lastError)
        assertEquals(null, subscriptions.value?.lastAttemptMillis)
    }

    private fun subscription() = Subscription(
        id = id,
        name = "Primary",
        urlSecretRef = "subscription-primary",
    )

    private class FakeSubscriptions(
        initial: Subscription,
        private val url: String = "https://subscriptions.example/list",
    ) : SubscriptionRepository {
        var value: Subscription? = initial
        override suspend fun upsert(subscription: Subscription, url: String?) {
            value = subscription
        }
        override suspend fun get(id: SubscriptionId) = value?.takeIf { it.id == id }
        override suspend fun getUrl(subscription: Subscription) = url
        override suspend fun list() = listOfNotNull(value)
        override suspend fun delete(id: SubscriptionId) {
            if (value?.id == id) value = null
        }
    }

    private class FakeProfiles : ProfileRepository {
        val replaced = mutableListOf<List<StoredProfile>>()
        val existing = mutableMapOf<ProfileId, VpnProfile>()
        override suspend fun upsert(profile: VpnProfile, sensitive: SensitiveProfileConfig) = Unit
        override suspend fun get(profileId: ProfileId): VpnProfile? = existing[profileId]
        override suspend fun getSensitive(profile: VpnProfile) = error("not used")
        override suspend fun list(): List<VpnProfile> = emptyList()
        override suspend fun delete(profileId: ProfileId) = Unit
        override suspend fun replaceSubscriptionProfiles(
            subscriptionId: String,
            profiles: List<StoredProfile>,
        ) {
            replaced += profiles
            profiles.forEach { existing[it.profile.id] = it.profile }
        }
    }
}