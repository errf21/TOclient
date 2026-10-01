package com.toclient.core.backup

import com.toclient.core.model.ProfileId
import com.toclient.core.model.VpnProfile
import com.toclient.core.subscription.Subscription
import com.toclient.core.subscription.SubscriptionId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class TocBackupCodecTest {
    private val codec = TocBackupCodec()
    private val password = "correct horse battery staple".toCharArray()
    private val payload = BackupPayload(
        createdAtMillis = 1234L,
        profiles = listOf(
            BackupProfile(
                VpnProfile(ProfileId("profile-1"), "Primary", secretRef = "secret-1"),
                """{"outbounds":[{"protocol":"freedom"}],"password":"top-secret"}""",
            ),
        ),
        subscriptions = listOf(
            BackupSubscription(
                Subscription(SubscriptionId("sub-1"), "Source", "url-secret"),
                "https://subscriptions.example/list?token=private-token",
            ),
        ),
    )

    @Test
    fun `encrypted independent backup round trips without plaintext secrets`() {
        val encoded = codec.encode(payload, password)

        assertContentEquals("TOCLIENT-BACKUP\n".toByteArray(), encoded.copyOfRange(0, 16))
        val visible = encoded.toString(Charsets.UTF_8)
        assertFalse("top-secret" in visible)
        assertFalse("private-token" in visible)
        assertEquals(payload, codec.decode(encoded, password))
    }

    @Test
    fun `wrong password and foreign formats are rejected`() {
        val encoded = codec.encode(payload, password)

        assertFailsWith<IllegalArgumentException> {
            codec.decode(encoded, "incorrect password".toCharArray())
        }
        assertFailsWith<IllegalArgumentException> {
            codec.decode("foreign-backup".toByteArray(), password)
        }
    }
}