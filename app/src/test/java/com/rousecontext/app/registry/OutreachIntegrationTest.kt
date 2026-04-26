package com.rousecontext.app.registry

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.api.LaunchRequestNotifierApi
import com.rousecontext.app.state.IntegrationSettingsStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OutreachIntegrationTest {

    private lateinit var context: Context
    private lateinit var settingsStore: IntegrationSettingsStore
    private lateinit var scope: CoroutineScope
    private lateinit var job: Job

    @Before
    fun setUp() = runBlocking {
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

    @Test
    fun `exposes canonical outreach metadata`() {
        val integration = OutreachIntegration(context, settingsStore, NoopNotifier, scope)

        assertEquals("outreach", integration.id)
        assertEquals("Outreach", integration.displayName)
        assertEquals("/outreach", integration.path)
        assertEquals("setup", integration.onboardingRoute)
        assertEquals("settings", integration.settingsRoute)
        assertTrue(integration.description.isNotBlank())
    }

    @Test
    fun `provider id matches integration id`() {
        val integration = OutreachIntegration(context, settingsStore, NoopNotifier, scope)
        assertEquals("outreach", integration.provider.id)
    }

    @Test
    fun `isAvailable always true`() = runBlocking {
        val integration = OutreachIntegration(context, settingsStore, NoopNotifier, scope)
        assertTrue(integration.isAvailable())
    }

    @Test
    fun `directLaunchEnabled starts false and follows settings store`() = runBlocking {
        val integration = OutreachIntegration(context, settingsStore, NoopNotifier, scope)

        assertFalse(integration.directLaunchEnabled.value)

        settingsStore.setBoolean(
            integration.id,
            IntegrationSettingsStore.KEY_DIRECT_LAUNCH_ENABLED,
            true
        )

        withTimeout(TIMEOUT_MS) {
            integration.directLaunchEnabled.first { it }
        }
        assertTrue(integration.directLaunchEnabled.value)

        settingsStore.setBoolean(
            integration.id,
            IntegrationSettingsStore.KEY_DIRECT_LAUNCH_ENABLED,
            false
        )
        withTimeout(TIMEOUT_MS) {
            integration.directLaunchEnabled.first { !it }
        }
        assertFalse(integration.directLaunchEnabled.value)
    }

    @Test
    fun `awaitReady completes after first settings emission`() = runBlocking {
        // The default Robolectric IntegrationSettingsStore emits `false` for any
        // unset key, which is a valid first emission. awaitReady must unblock.
        val integration = OutreachIntegration(context, settingsStore, NoopNotifier, scope)
        withTimeout(TIMEOUT_MS) { integration.awaitReady() }
        assertTrue(
            "awaitReadyBlocking should also report ready",
            integration.awaitReadyBlocking(TIMEOUT_MS)
        )
    }

    @Test
    fun `awaitReady is idempotent across multiple calls`() = runBlocking {
        val integration = OutreachIntegration(context, settingsStore, NoopNotifier, scope)
        withTimeout(TIMEOUT_MS) { integration.awaitReady() }
        // A second call must return immediately.
        withTimeout(TIMEOUT_MS) { integration.awaitReady() }
        assertTrue(integration.awaitReadyBlocking(SHORT_TIMEOUT_MS))
    }

    @Test
    fun `awaitReady does not complete before first emission lands`() = runBlocking {
        // Slow settings store: observeBoolean emits only after the gate completes.
        // Pre-fix, _directLaunchEnabled stays at its `false` default and any
        // synchronous read by canLaunchDirectly would mis-route the call.
        val gate = CompletableDeferred<Unit>()
        val slowStore = mockk<IntegrationSettingsStore>()
        every {
            slowStore.observeBoolean(
                "outreach",
                IntegrationSettingsStore.KEY_DIRECT_LAUNCH_ENABLED,
                any()
            )
        } returns flow {
            gate.await()
            emit(true)
        }

        val integration = OutreachIntegration(context, slowStore, NoopNotifier, scope)
        // While gated, awaitReadyBlocking with a short timeout reports false.
        assertFalse(integration.awaitReadyBlocking(SHORT_TIMEOUT_MS))

        // Open the gate; awaitReady completes and the loaded state is reflected.
        gate.complete(Unit)
        withTimeout(TIMEOUT_MS) { integration.awaitReady() }
        assertTrue(integration.directLaunchEnabled.value)
    }

    @Test
    fun `directLaunchEnabled stays false until first emission lands`() = runBlocking {
        // Regression for issue #419 finding #2: the very first tool call after
        // process spawn must not see the default `false` opt-in if the user
        // had actually enabled direct launch on disk. The fix routes reads
        // through `awaitReady`, so before the first emission lands the store
        // value is the synchronous default and only flips after awaitReady
        // returns.
        val gate = CompletableDeferred<Unit>()
        val slowStore = mockk<IntegrationSettingsStore>()
        every {
            slowStore.observeBoolean(
                "outreach",
                IntegrationSettingsStore.KEY_DIRECT_LAUNCH_ENABLED,
                any()
            )
        } returns flow {
            gate.await()
            emit(true)
        }

        val integration = OutreachIntegration(context, slowStore, NoopNotifier, scope)

        // Pre-load: directLaunchEnabled is the default `false`, awaitReady
        // has not signalled.
        assertFalse(integration.directLaunchEnabled.value)
        assertFalse(integration.awaitReadyBlocking(SHORT_TIMEOUT_MS))

        // Open the gate; awaitReady completes and the loaded state is reflected.
        gate.complete(Unit)
        withTimeout(TIMEOUT_MS) { integration.awaitReady() }
        assertTrue(integration.directLaunchEnabled.value)
    }

    private object NoopNotifier : LaunchRequestNotifierApi {
        override fun postLaunchApp(
            launchIntent: Intent,
            packageName: String,
            clientName: String?
        ): Int = 0

        override fun postOpenLink(viewIntent: Intent, url: String, clientName: String?): Int = 0
    }

    companion object {
        private const val TIMEOUT_MS = 5_000L
        private const val SHORT_TIMEOUT_MS = 50L
    }
}
