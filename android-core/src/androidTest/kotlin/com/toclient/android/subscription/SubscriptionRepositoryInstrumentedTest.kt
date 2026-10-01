package com.toclient.android.subscription

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.toclient.core.subscription.Subscription
import com.toclient.core.subscription.SubscriptionId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubscriptionRepositoryInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val repository = AndroidSubscriptionRepository(context)
    private val id = SubscriptionId("instrumented-subscription")
    private val secretRef = "instrumented-subscription-url"

    @After
    fun cleanup() {
        runBlocking { repository.delete(id) }
    }

    @Test
    fun roundTripsMetadataAndEncryptsSubscriptionUrl() = runBlocking {
        val subscription = Subscription(id, "Instrumented", secretRef)
        val url = "https://subscriptions.example/list?token=secret-value"

        repository.upsert(subscription, url)

        assertEquals(subscription, repository.get(id))
        assertEquals(url, repository.getUrl(subscription))
        val encryptedFile = context.noBackupFilesDir
            .resolve("profile-secrets/$secretRef.enc")
        assertFalse(encryptedFile.readText().contains("secret-value"))
    }
}