package com.rousecontext.app.registry

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.api.LaunchRequestNotifierApi
import com.rousecontext.app.state.IntegrationSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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
    }
}
