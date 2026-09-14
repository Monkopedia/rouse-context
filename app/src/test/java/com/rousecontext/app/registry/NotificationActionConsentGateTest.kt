package com.rousecontext.app.registry

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.app.state.IntegrationSettingsStore
import com.rousecontext.integrations.notifications.NotificationDao
import com.rousecontext.integrations.notifications.NotificationDatabase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * "Allow AI to act on notifications" must gate the notification action and
 * dismiss tools at the MCP provider boundary.
 *
 * Regression for the fail-open defect: the switch was written to
 * [IntegrationSettingsStore.KEY_ALLOW_ACTIONS], displayed by the setup screen,
 * and never read by anything that constructed the provider — `AppModule`
 * built `NotificationIntegration` with three positional arguments, so
 * `allowActions` silently took its `true` default while the UI default was
 * `false`. Every assertion here is on what a tool call *does*; asserting the
 * preference round-trips would have passed against the broken build.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationActionConsentGateTest {

    private lateinit var context: Context
    private lateinit var db: NotificationDatabase
    private lateinit var dao: NotificationDao
    private lateinit var settingsStore: IntegrationSettingsStore
    private lateinit var scope: CoroutineScope
    private lateinit var job: Job

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        db = NotificationDatabase.createInMemory(context)
        dao = db.notificationDao()
        settingsStore = IntegrationSettingsStore(context)
        job = SupervisorJob()
        scope = CoroutineScope(Dispatchers.Unconfined + job)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun integration(): NotificationIntegration = NotificationIntegration(
        context = context,
        dao = dao,
        settingsStore = settingsStore,
        appScope = scope
    )

    /**
     * A settings store with nothing ever written to it: every key resolves to
     * the default the caller asked for. Used instead of the real DataStore so
     * the "fresh install" case cannot be contaminated by another test's write.
     */
    private fun unsetStore(): IntegrationSettingsStore = mockk {
        every { observeBoolean(any(), any(), any()) } answers { flowOf(thirdArg()) }
    }

    private suspend fun setAllowActions(value: Boolean) {
        settingsStore.setBoolean(
            "notifications",
            IntegrationSettingsStore.KEY_ALLOW_ACTIONS,
            value
        )
    }

    private fun harnessFor(integration: NotificationIntegration): ConsentGateHarness =
        ConsentGateHarness().apply { register(integration.provider) }

    /** Flip the stored consent and wait for the integration to observe it. */
    private suspend fun flipConsent(integration: NotificationIntegration, value: Boolean) {
        setAllowActions(value)
        withTimeout(TIMEOUT_MS) { integration.allowActions.first { it == value } }
    }

    @Test
    fun `perform_notification_action is refused when consent is off`() = runBlocking {
        setAllowActions(false)
        val harness = harnessFor(integration())

        val result = harness.callTool(
            "perform_notification_action",
            buildJsonObject {
                put("notification_key", JsonPrimitive("0|com.example|1|null|0"))
                put("action_index", JsonPrimitive(0))
            }
        )

        assertTrue(
            "perform_notification_action must be refused without consent, got " +
                ConsentGateHarness.bodyOf(result),
            result.isError == true
        )
        assertTrue(
            ConsentGateHarness.bodyOf(result).contains("disabled by the user")
        )
    }

    @Test
    fun `dismiss_notification is refused when consent is off`() = runBlocking {
        setAllowActions(false)
        val harness = harnessFor(integration())

        val result = harness.callTool(
            "dismiss_notification",
            buildJsonObject {
                put("notification_key", JsonPrimitive("0|com.example|1|null|0"))
            }
        )

        assertTrue(
            "dismiss_notification must be refused without consent, got " +
                ConsentGateHarness.bodyOf(result),
            result.isError == true
        )
        assertTrue(
            ConsentGateHarness.bodyOf(result).contains("disabled by the user")
        )
    }

    @Test
    fun `action tools are unset-safe - no stored preference means no consent`() = runBlocking {
        // Nothing ever written to KEY_ALLOW_ACTIONS: a fresh install must not
        // hand an AI client the ability to press notification buttons.
        val harness = harnessFor(
            NotificationIntegration(
                context = context,
                dao = dao,
                settingsStore = unsetStore(),
                appScope = scope
            )
        )

        val result = harness.callTool(
            "perform_notification_action",
            buildJsonObject {
                put("notification_key", JsonPrimitive("0|com.example|1|null|0"))
                put("action_index", JsonPrimitive(0))
            }
        )

        assertTrue(result.isError == true)
        assertTrue(ConsentGateHarness.bodyOf(result).contains("disabled by the user"))
    }

    @Test
    fun `action tools run when consent is on`() = runBlocking {
        setAllowActions(true)
        val harness = harnessFor(integration())

        val action = harness.callTool(
            "perform_notification_action",
            buildJsonObject {
                put("notification_key", JsonPrimitive("0|com.example|1|null|0"))
                put("action_index", JsonPrimitive(0))
            }
        )
        // No listener service is running under Robolectric, so the call reaches
        // the action performer and reports failure — the point is that it got
        // past the consent gate rather than being refused by it.
        assertFalse(
            "with consent on the tool must not be gate-refused",
            ConsentGateHarness.bodyOf(action).contains("disabled by the user")
        )

        val dismiss = harness.callTool(
            "dismiss_notification",
            buildJsonObject {
                put("notification_key", JsonPrimitive("0|com.example|1|null|0"))
            }
        )
        assertFalse(
            "with consent on the tool must not be gate-refused",
            ConsentGateHarness.bodyOf(dismiss).contains("disabled by the user")
        )
    }

    @Test
    fun `revoking consent takes effect without rebuilding the integration`() = runBlocking {
        setAllowActions(true)
        val integration = integration()
        val harness = harnessFor(integration)

        val args = buildJsonObject {
            put("notification_key", JsonPrimitive("0|com.example|1|null|0"))
            put("action_index", JsonPrimitive(0))
        }

        val allowed = harness.callTool("perform_notification_action", args)
        assertFalse(
            ConsentGateHarness.bodyOf(allowed).contains("disabled by the user")
        )

        // Same integration instance, same registered handler: flipping the
        // stored preference must gate the very next call. The direct-launch
        // opt-in behaves this way (it is read per call), and consent controls
        // must not need an app restart to take hold.
        flipConsent(integration, false)

        val refused = harness.callTool("perform_notification_action", args)
        assertTrue(
            "consent revocation must apply to the next call, got " +
                ConsentGateHarness.bodyOf(refused),
            refused.isError == true
        )
        assertTrue(ConsentGateHarness.bodyOf(refused).contains("disabled by the user"))
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
