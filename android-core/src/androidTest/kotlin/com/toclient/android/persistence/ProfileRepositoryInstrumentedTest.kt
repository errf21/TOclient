package com.toclient.android.persistence

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.toclient.core.model.ProfileId
import com.toclient.core.model.ProfileSource
import com.toclient.core.model.SensitiveProfileConfig
import com.toclient.core.model.StoredProfile
import com.toclient.core.model.VpnProfile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileRepositoryInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val repository = AndroidProfileRepository(context)
    private val profileId = ProfileId("instrumented-profile")

    @After
    fun cleanup() {
        runBlocking {
            repository.delete(profileId)
            repository.delete(ProfileId("subscription-a-profile"))
            repository.delete(ProfileId("subscription-b-profile"))
        }
    }

    @Test
    fun replacingOneSubscriptionNeverTouchesAnotherSubscription() = runBlocking {
        val a = VpnProfile(
            id = ProfileId("subscription-a-profile"),
            name = "A server",
            secretRef = "subscription-a-secret",
            source = ProfileSource.Subscription("subscription-a"),
        )
        val b = VpnProfile(
            id = ProfileId("subscription-b-profile"),
            name = "B server",
            secretRef = "subscription-b-secret",
            source = ProfileSource.Subscription("subscription-b"),
        )
        repository.replaceSubscriptionProfiles("subscription-a", listOf(
            StoredProfile(a, SensitiveProfileConfig("{\"outbounds\":[]}")),
        ))
        repository.replaceSubscriptionProfiles("subscription-b", listOf(
            StoredProfile(b, SensitiveProfileConfig("{\"outbounds\":[]}")),
        ))

        repository.replaceSubscriptionProfiles("subscription-a", emptyList())

        assertEquals(null, repository.get(a.id))
        assertEquals(b, repository.get(b.id))
    }

    @Test
    fun roundTripsMetadataAndEncryptedConfiguration() = runBlocking {
        val profile = VpnProfile(
            id = profileId,
            name = "Instrumented",
            secretRef = "instrumented-secret",
        )
        val rawConfig = """{"outbounds":[{"protocol":"freedom"}],"token":"secret-value"}"""

        repository.upsert(profile, SensitiveProfileConfig(rawConfig))

        assertEquals(profile, repository.get(profileId))
        assertEquals(rawConfig, repository.getSensitive(profile).xrayJson)
        val encryptedFile = context.noBackupFilesDir
            .resolve("profile-secrets/instrumented-secret.enc")
        assertFalse(encryptedFile.readText().contains("secret-value"))
    }
}