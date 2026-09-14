package com.rousecontext.app.registry

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.api.LaunchRequestNotifierApi
import com.rousecontext.app.state.IntegrationSettingsStore
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
import org.robolectric.Shadows

/**
 * The Outreach "Do Not Disturb" toggle must gate the DND tools at the MCP
 * provider boundary.
 *
 * Regression for the fail-open defect: `OutreachIntegration` passed
 * `dndEnabled = isDndPermissionGranted()` — the OS permission only. The user's
 * own [IntegrationSettingsStore.KEY_DND_TOGGLED] control was written by the
 * setup screen and never consulted, so granting the OS permission once was
 * enough to let an AI client silence the phone regardless of the toggle.
 *
 * The permission itself was also a construction-time snapshot (a `val`
 * argument), unlike `canLaunchDirectly`, which is a per-call lambda. Both
 * inputs are asserted live here.
 */
@RunWith(RobolectricTestRunner::class)
class OutreachDndConsentGateTest {

    private lateinit var context: Context
    private lateinit var settingsStore: IntegrationSettingsStore
    private lateinit var scope: CoroutineScope
    private lateinit var job: Job

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        settingsStore = IntegrationSettingsStore(context)
        job = SupervisorJob()
        scope = CoroutineScope(Dispatchers.Unconfined + job)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun setDndPermission(granted: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java)
        Shadows.shadowOf(nm).setNotificationPolicyAccessGranted(granted)
    }

    private suspend fun setDndToggle(value: Boolean) {
        settingsStore.setBoolean("outreach", IntegrationSettingsStore.KEY_DND_TOGGLED, value)
    }

    private fun integration(): OutreachIntegration =
        OutreachIntegration(context, settingsStore, NoopNotifier, scope)

    /**
     * A settings store with nothing ever written to it: every key resolves to
     * the default the caller asked for. Used instead of the real DataStore so
     * the "fresh install" case cannot be contaminated by another test's write.
     */
    private fun unsetStore(): IntegrationSettingsStore = mockk {
        every { observeBoolean(any(), any(), any()) } answers { flowOf(thirdArg()) }
    }

    private fun harnessFor(integration: OutreachIntegration): ConsentGateHarness =
        ConsentGateHarness().apply { register(integration.provider) }

    /** Flip the stored toggle and wait for the integration to observe it. */
    private suspend fun flipToggle(integration: OutreachIntegration, value: Boolean) {
        setDndToggle(value)
        withTimeout(TIMEOUT_MS) { integration.dndToggled.first { it == value } }
    }

    private val setDndArgs = buildJsonObject {
        put("enabled", JsonPrimitive(true))
        put("mode", JsonPrimitive("total_silence"))
    }

    @Test
    fun `set_dnd_state is refused when the user toggle is off`() = runBlocking {
        setDndPermission(true)
        setDndToggle(false)

        val result = harnessFor(integration()).callTool("set_dnd_state", setDndArgs)

        assertTrue(
            "set_dnd_state must be refused when the DND toggle is off, got " +
                ConsentGateHarness.bodyOf(result),
            result.isError == true
        )
        assertTrue(
            ConsentGateHarness.bodyOf(result).contains(DISABLED_MARKER)
        )
    }

    @Test
    fun `get_dnd_state is refused when the user toggle is off`() = runBlocking {
        setDndPermission(true)
        setDndToggle(false)

        val result = harnessFor(integration()).callTool("get_dnd_state")

        assertTrue(
            "get_dnd_state must be refused when the DND toggle is off, got " +
                ConsentGateHarness.bodyOf(result),
            result.isError == true
        )
        assertTrue(ConsentGateHarness.bodyOf(result).contains(DISABLED_MARKER))
    }

    @Test
    fun `dnd tools are unset-safe - no stored preference means no consent`() = runBlocking {
        setDndPermission(true)
        val integration = OutreachIntegration(context, unsetStore(), NoopNotifier, scope)

        val result = harnessFor(integration).callTool("set_dnd_state", setDndArgs)

        assertTrue(result.isError == true)
        assertTrue(ConsentGateHarness.bodyOf(result).contains(DISABLED_MARKER))
    }

    @Test
    fun `dnd tools work when permission is granted and the toggle is on`() = runBlocking {
        setDndPermission(true)
        setDndToggle(true)

        val harness = harnessFor(integration())

        val get = harness.callTool("get_dnd_state")
        assertFalse(
            "get_dnd_state must run with consent, got " + ConsentGateHarness.bodyOf(get),
            get.isError == true
        )

        val set = harness.callTool("set_dnd_state", setDndArgs)
        assertFalse(
            "set_dnd_state must run with consent, got " + ConsentGateHarness.bodyOf(set),
            set.isError == true
        )
        val nm = context.getSystemService(NotificationManager::class.java)
        assertTrue(
            "DND should actually have been applied",
            nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        )
    }

    @Test
    fun `toggling consent on takes effect without rebuilding the integration`() = runBlocking {
        setDndPermission(true)
        setDndToggle(false)

        val integration = integration()
        val harness = harnessFor(integration)

        val refused = harness.callTool("set_dnd_state", setDndArgs)
        assertTrue(refused.isError == true)

        flipToggle(integration, true)

        val allowed = harness.callTool("set_dnd_state", setDndArgs)
        assertFalse(
            "toggle must take effect on the next call, got " +
                ConsentGateHarness.bodyOf(allowed),
            allowed.isError == true
        )
    }

    @Test
    fun `revoking the OS permission after construction disables the tools`() = runBlocking {
        // `dndEnabled` used to be evaluated once at DI time, so a permission
        // revoked later in the app's lifetime left the tools live.
        setDndPermission(true)
        setDndToggle(true)

        val harness = harnessFor(integration())
        assertFalse(harness.callTool("set_dnd_state", setDndArgs).isError == true)

        setDndPermission(false)

        val refused = harness.callTool("set_dnd_state", setDndArgs)
        assertTrue(
            "revoked permission must apply to the next call, got " +
                ConsentGateHarness.bodyOf(refused),
            refused.isError == true
        )
    }

    @Test
    fun `granting the OS permission after construction enables the tools`() = runBlocking {
        // The mirror case: the permission snapshot also meant a grant made
        // after process start did nothing until the app was restarted.
        setDndPermission(false)
        setDndToggle(true)

        val harness = harnessFor(integration())
        assertTrue(harness.callTool("set_dnd_state", setDndArgs).isError == true)

        setDndPermission(true)

        val allowed = harness.callTool("set_dnd_state", setDndArgs)
        assertFalse(
            "a later grant must apply to the next call, got " +
                ConsentGateHarness.bodyOf(allowed),
            allowed.isError == true
        )
    }

    private object NoopNotifier : LaunchRequestNotifierApi {
        override fun postLaunchApp(
            launchIntent: Intent,
            packageName: String,
            clientName: String?
        ): Int = 0

        override fun postOpenLink(viewIntent: Intent, url: String, clientName: String?): Int = 0
    }

    private companion object {
        const val DISABLED_MARKER = "Do Not Disturb"
        const val TIMEOUT_MS = 5_000L
    }
}
