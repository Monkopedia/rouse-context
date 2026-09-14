package com.rousecontext.app.registry

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.app.state.IntegrationSettingsStore
import com.rousecontext.integrations.notifications.NotificationDao
import com.rousecontext.integrations.notifications.NotificationDatabase
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
class NotificationIntegrationTest {

    private lateinit var context: Context
    private lateinit var db: NotificationDatabase
    private lateinit var dao: NotificationDao
    private lateinit var settingsStore: IntegrationSettingsStore
    private lateinit var scope: CoroutineScope
    private lateinit var job: Job

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
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

    private fun newIntegration(): NotificationIntegration = NotificationIntegration(
        context = context,
        dao = dao,
        settingsStore = settingsStore,
        appScope = scope
    )

    @Test
    fun `exposes canonical notification metadata`() {
        val integration = newIntegration()

        assertEquals("notifications", integration.id)
        assertEquals("Notifications", integration.displayName)
        assertEquals("/notifications", integration.path)
        assertEquals("setup", integration.onboardingRoute)
        assertEquals("settings", integration.settingsRoute)
        assertTrue(integration.description.isNotBlank())
    }

    @Test
    fun `provider id matches integration id`() {
        val integration = newIntegration()
        assertEquals("notifications", integration.provider.id)
    }

    @Test
    fun `isAvailable always true`() = runBlocking {
        assertTrue(newIntegration().isAvailable())
    }

    @Test
    fun `isPermissionGranted false when listener not registered`() {
        val integration = newIntegration()
        // Robolectric default: enabled_notification_listeners is unset.
        assertFalse(integration.isPermissionGranted())
    }

    @Test
    fun `isPermissionGranted true when listener registered in Settings Secure`() {
        val integration = newIntegration()

        // Fake a grant by writing the component flattening into the
        // "enabled_notification_listeners" secure setting that the code reads.
        val component = "${context.packageName}/" +
            "com.rousecontext.integrations.notifications.NotificationCaptureService"
        Settings.Secure.putString(
            context.contentResolver,
            "enabled_notification_listeners",
            component
        )

        assertTrue(integration.isPermissionGranted())
    }

    @Test
    fun `allow-actions consent tracks the settings store`() = runBlocking {
        // The old version of this test only constructed the integration with
        // an `allowActions` argument and asserted nothing about it — which is
        // precisely why the fail-open wiring defect went unnoticed. See
        // NotificationActionConsentGateTest for the provider-boundary
        // assertions; this one covers the integration's own accessor.
        val integration = newIntegration()
        assertFalse("consent must default to denied", integration.isActionsAllowed())

        settingsStore.setBoolean(
            integration.id,
            IntegrationSettingsStore.KEY_ALLOW_ACTIONS,
            true
        )
        withTimeout(TIMEOUT_MS) { integration.allowActions.first { it } }
        assertTrue(integration.isActionsAllowed())

        settingsStore.setBoolean(
            integration.id,
            IntegrationSettingsStore.KEY_ALLOW_ACTIONS,
            false
        )
        withTimeout(TIMEOUT_MS) { integration.allowActions.first { !it } }
        assertFalse(integration.isActionsAllowed())
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
