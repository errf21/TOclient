package com.toclient.android

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.toclient.android.persistence.AndroidProfileRepository
import com.toclient.android.subscription.AndroidSubscriptionRepository
import com.toclient.core.model.ProfileId
import com.toclient.core.model.ProfileSource
import com.toclient.core.model.SensitiveProfileConfig
import com.toclient.core.model.StoredProfile
import com.toclient.core.model.VpnProfile
import com.toclient.core.subscription.Subscription
import com.toclient.core.subscription.SubscriptionId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductHierarchyInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val profiles = AndroidProfileRepository(context)
    private val subscriptions = AndroidSubscriptionRepository(context)
    private val alpha = SubscriptionId("test-alpha")
    private val beta = SubscriptionId("test-beta")
    private val germany = ProfileId("test-germany-01")
    private val finland = ProfileId("test-finland-01")

    @Test
    fun groupedSelectionSurvivesRecreationAndDeletingAlphaLeavesBeta() = runBlocking {
        try {
            subscriptions.upsert(
                Subscription(alpha, "Alpha", "test-alpha-url", profileCount = 1, sourceHost = "alpha.example"),
                "https://alpha.example/sub",
            )
            subscriptions.upsert(
                Subscription(beta, "Beta", "test-beta-url", profileCount = 1, sourceHost = "beta.example"),
                "https://beta.example/sub",
            )
            val alphaProfile = VpnProfile(
                germany, "Germany 01", secretRef = "test-germany-secret",
                source = ProfileSource.Subscription(alpha.value), protocolLabel = "vless",
                endpointLabel = "de.example:443", transportLabel = "tcp", securityLabel = "reality",
            )
            val betaProfile = VpnProfile(
                finland, "Finland 01", secretRef = "test-finland-secret",
                source = ProfileSource.Subscription(beta.value), protocolLabel = "trojan",
                endpointLabel = "fi.example:443", transportLabel = "ws", securityLabel = "tls",
            )
            profiles.replaceSubscriptionProfiles(alpha.value, listOf(
                StoredProfile(alphaProfile, SensitiveProfileConfig("{\"outbounds\":[]}")),
            ))
            profiles.replaceSubscriptionProfiles(beta.value, listOf(
                StoredProfile(betaProfile, SensitiveProfileConfig("{\"outbounds\":[]}")),
            ))
            context.getSharedPreferences("to_client_ui", Context.MODE_PRIVATE).edit()
                .putString("selected_subscription", alpha.value)
                .putString("selected_profile", germany.value)
                .commit()

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                SystemClock.sleep(1_500)
                assertHomeSelection(scenario)
                scenario.recreate()
                SystemClock.sleep(1_500)
                assertHomeSelection(scenario)
            }

            profiles.replaceSubscriptionProfiles(alpha.value, emptyList())
            subscriptions.delete(alpha)
            assertEquals(betaProfile, profiles.get(finland))
            assertTrue(subscriptions.list().any { it.id == beta })
        } finally {
            profiles.replaceSubscriptionProfiles(alpha.value, emptyList())
            profiles.replaceSubscriptionProfiles(beta.value, emptyList())
            subscriptions.delete(alpha)
            subscriptions.delete(beta)
            context.getSharedPreferences("to_client_ui", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun aboutShowsDeveloperThanksAndDeveloperTapOpensTelegram() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    findByContentDescription(activity.window.decorView, "Settings")?.performClick()
                }
                SystemClock.sleep(300)
                scenario.onActivity { activity ->
                    findText(activity.window.decorView, "About TO Client")
                        ?.let(::clickClickableAncestor)
                }
                SystemClock.sleep(300)
                scenario.onActivity { activity ->
                    val text = collectText(activity.window.decorView)
                    assertTrue(text.contains("Developer"))
                    assertTrue(text.contains("@Espierz"))
                    assertTrue(text.contains("With special thanks to HO3EIN.zmn"))
                    val developer = findByContentDescription(activity.window.decorView, "Developer: @Espierz")
                    assertTrue(developer?.isClickable == true)
                    developer?.performClick()
                }
            SystemClock.sleep(300)
            val intent = MainActivity.developerIntent()
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("https://t.me/Espierz", intent.dataString)
        }
    }

    private fun assertHomeSelection(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            val text = collectText(activity.window.decorView)
            assertTrue(text.contains("Alpha"))
            assertTrue(text.contains("Germany 01"))
            assertTrue(text.contains("VLESS"))
            assertTrue(text.contains("REALITY"))
        }
    }

    private fun findText(view: View, text: String): View? {
        if (view is TextView && view.text.toString() == text) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findText(view.getChildAt(index), text)?.let { return it }
        }
        return null
    }

    private fun findByContentDescription(view: View, description: String): View? {
        if (view.contentDescription?.toString() == description) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findByContentDescription(view.getChildAt(index), description)?.let { return it }
            }
        }
        return null
    }

    private fun clickClickableAncestor(view: View) {
        var current: View? = view
        while (current != null && !current.isClickable) current = current.parent as? View
        current?.performClick()
    }

    private fun collectText(view: View): String = buildString {
        if (view is TextView) append(view.text).append('\n')
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) append(collectText(view.getChildAt(index)))
        }
    }
}
