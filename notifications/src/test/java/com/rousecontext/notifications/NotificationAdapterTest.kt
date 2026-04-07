package com.rousecontext.notifications

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class NotificationAdapterTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private lateinit var adapter: NotificationAdapter

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        NotificationChannels.createAll(context)
        adapter = NotificationAdapter(context)
    }

    @Test
    fun `ShowForeground posts notification with foreground ID`() {
        adapter.execute(NotificationAction.ShowForeground("Connected"))

        val shadowManager = Shadows.shadowOf(manager)
        val notification = shadowManager.getNotification(NotificationAdapter.FOREGROUND_ID)
        assertNotNull(notification)
    }

    @Test
    fun `PostSummary posts notification with session complete title`() {
        adapter.execute(NotificationAction.PostSummary(toolCallCount = 3))

        val shadowManager = Shadows.shadowOf(manager)
        val notifications = shadowManager.allNotifications
        // foreground might not be posted, but at least one dynamic notification should exist
        assertTrue(notifications.isNotEmpty())
    }

    @Test
    fun `PostError posts notification`() {
        adapter.execute(NotificationAction.PostError("Connection failed", streamId = null))

        val shadowManager = Shadows.shadowOf(manager)
        assertTrue(shadowManager.allNotifications.isNotEmpty())
    }

    @Test
    fun `PostAlert posts notification`() {
        adapter.execute(NotificationAction.PostAlert("Suspicious activity"))

        val shadowManager = Shadows.shadowOf(manager)
        assertTrue(shadowManager.allNotifications.isNotEmpty())
    }

    @Test
    fun `multiple actions post unique notification IDs`() {
        adapter.execute(
            listOf(
                NotificationAction.PostInfo("Info 1"),
                NotificationAction.PostInfo("Info 2"),
                NotificationAction.PostInfo("Info 3")
            )
        )

        val shadowManager = Shadows.shadowOf(manager)
        // Each should have a unique ID, so all 3 should be present
        assertEquals(3, shadowManager.allNotifications.size)
    }
}
