package com.rousecontext.app.state

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [LiveBooleanSetting] gates MCP tool calls on a user consent toggle, so a
 * *failed* read has to produce a refusal the caller can see.
 *
 * Issue #751: it failed closed but denied by suspending forever. Preferences
 * DataStore surfaces a corrupt or unreadable file as an exception on
 * `dataStore.data`, which killed the collector before `signalReady()` ran, so
 * `awaitReady()` awaited a `CompletableDeferred` that was never completed and
 * never cancelled. `perform_notification_action`, `dismiss_notification`,
 * `get_dnd_state`, `set_dnd_state` and the direct-launch path all hung rather
 * than returning their refusal.
 *
 * The [withTimeout] in the failure tests is load-bearing: without it the
 * regression is a hung test run rather than a red assertion, and a hang reads
 * as flake. Nothing here wraps the call under test in `runCatching` or a broad
 * `catch` — that would let these tests pass on the very failure they exist to
 * catch.
 */
@RunWith(RobolectricTestRunner::class)
class LiveBooleanSettingTest {

    private lateinit var job: Job
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        job = SupervisorJob()
        scope = CoroutineScope(Dispatchers.Unconfined + job)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** A store whose backing flow fails the way a corrupt DataStore file does. */
    private fun throwingStore(): IntegrationSettingsStore = mockk {
        every { observeBoolean(any(), any(), any()) } returns
            flow { throw CorruptStoreException() }
    }

    private fun storeEmitting(value: Boolean): IntegrationSettingsStore = mockk {
        every { observeBoolean(any(), any(), any()) } returns flowOf(value)
    }

    /** A store that is still loading: no value yet, and no failure either. */
    private fun pendingStore(): IntegrationSettingsStore = mockk {
        every { observeBoolean(any(), any(), any()) } returns flow { awaitCancellation() }
    }

    private fun setting(store: IntegrationSettingsStore): LiveBooleanSetting = LiveBooleanSetting(
        settingsStore = store,
        integrationId = "outreach",
        key = IntegrationSettingsStore.KEY_DIRECT_LAUNCH_ENABLED,
        default = false,
        appScope = scope
    )

    @Test
    fun `current returns the deny default when the settings read throws`() = runBlocking {
        val setting = setting(throwingStore())

        val allowed = withTimeout(TIMEOUT_MS) { setting.current() }

        assertFalse("a failed settings read must refuse, not allow", allowed)
    }

    @Test
    fun `awaitReadyBlocking reports ready when the settings read throws`() = runBlocking {
        val setting = setting(throwingStore())

        // The FCM wake path cannot suspend, so it takes the blocking gate. It
        // must not sit out its whole timeout on a read that has already failed.
        val ready = withContext(Dispatchers.IO) { setting.awaitReadyBlocking(TIMEOUT_MS) }

        assertTrue("a failed settings read must release the blocking gate", ready)
        assertFalse("the released value must still be the deny default", setting.value.value)
    }

    @Test
    fun `current returns the stored value once it loads`() = runBlocking {
        val setting = setting(storeEmitting(true))

        assertTrue(withTimeout(TIMEOUT_MS) { setting.current() })
    }

    @Test
    fun `current still waits while the store has not produced a value`() = runBlocking {
        // The gate exists so a tool call racing process spawn cannot read a
        // pre-load default. Releasing on a *failed* read must not turn into
        // releasing on an *unfinished* one.
        val setting = setting(pendingStore())

        assertNull(
            "current() must keep waiting while the read is still in flight",
            withTimeoutOrNull(PENDING_MS) { setting.current() }
        )
    }

    private class CorruptStoreException : IllegalStateException("unreadable preferences file")

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val PENDING_MS = 250L
    }
}
