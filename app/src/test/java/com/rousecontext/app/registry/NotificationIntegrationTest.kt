package com.rousecontext.app.registry

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.integrations.notifications.NotificationDao
import com.rousecontext.integrations.notifications.NotificationDatabase
import kotlinx.coroutines.runBlocking
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

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = NotificationDatabase.createInMemory(context)
        dao = db.notificationDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `exposes canonical notification metadata`() {
        val integration = NotificationIntegration(context, dao)

        assertEquals("notifications", integration.id)
        assertEquals("Notifications", integration.displayName)
        assertEquals("/notifications", integration.path)
        assertEquals("setup", integration.onboardingRoute)
        assertEquals("settings", integration.settingsRoute)
        assertTrue(integration.description.isNotBlank())
    }

    @Test
    fun `provider id matches integration id`() {
        val integration = NotificationIntegration(context, dao)
        assertEquals("notifications", integration.provider.id)
    }

    @Test
    fun `isAvailable always true`() = runBlocking {
        assertTrue(NotificationIntegration(context, dao).isAvailable())
    }

    @Test
    fun `isPermissionGranted false when listener not registered`() {
        val integration = NotificationIntegration(context, dao)
        // Robolectric default: enabled_notification_listeners is unset.
        assertFalse(integration.isPermissionGranted())
    }

    @Test
    fun `isPermissionGranted true when listener registered in Settings Secure`() {
        val integration = NotificationIntegration(context, dao)

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
    fun `constructor accepts allowActions override without crashing`() {
        // This exercises the branch that toggles action-execution tools in
        // the provider. We don't assert on the provider's private state;
        // constructing it is enough to cover the wiring.
        val integration = NotificationIntegration(context, dao, allowActions = false)
        assertEquals("notifications", integration.id)
    }
}
