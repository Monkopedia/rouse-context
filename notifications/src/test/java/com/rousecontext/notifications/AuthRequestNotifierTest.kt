package com.rousecontext.notifications

import android.app.Activity
import android.app.NotificationManager
import android.content.BroadcastReceiver
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
class AuthRequestNotifierTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private lateinit var notifier: AuthRequestNotifier

    /** Dummy receiver class for tests. */
    class TestReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            // No-op for testing
        }
    }

    /** Dummy activity class for tests. */
    class TestActivity : Activity()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        NotificationChannels.createAll(context)
        notifier = AuthRequestNotifier(
            context = context,
            receiverClass = TestReceiver::class.java,
            approveAction = "com.test.APPROVE",
            denyAction = "com.test.DENY",
            activityClass = TestActivity::class.java
        )
    }

    @Test
    fun `post creates a notification with correct channel`() {
        notifier.post("ABC-123", "health")

        val shadowManager = Shadows.shadowOf(manager)
        val notification = shadowManager.getNotification(AuthRequestNotifier.BASE_ID)
        assertNotNull("Notification should be posted", notification)
        assertEquals(
            NotificationChannels.AUTH_REQUEST_CHANNEL_ID,
            notification.channelId
        )
    }

    @Test
    fun `post includes display code in notification text`() {
        notifier.post("XYZ-789", "notifications")

        val shadowManager = Shadows.shadowOf(manager)
        val notification = shadowManager.getNotification(AuthRequestNotifier.BASE_ID)
        assertNotNull(notification)
        val extras = notification.extras
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        assertTrue(
            "Notification text should contain the display code, was: $text",
            text.contains("XYZ-789")
        )
    }

    @Test
    fun `post includes two action buttons`() {
        notifier.post("TEST", "health")

        val shadowManager = Shadows.shadowOf(manager)
        val notification = shadowManager.getNotification(AuthRequestNotifier.BASE_ID)
        assertNotNull(notification)
        assertEquals(2, notification.actions.size)
        assertEquals("Approve", notification.actions[0].title.toString())
        assertEquals("Deny", notification.actions[1].title.toString())
    }

    @Test
    fun `consecutive posts use different notification IDs`() {
        notifier.post("CODE-1", "health")
        notifier.post("CODE-2", "health")

        val shadowManager = Shadows.shadowOf(manager)
        val first = shadowManager.getNotification(AuthRequestNotifier.BASE_ID)
        val second = shadowManager.getNotification(AuthRequestNotifier.BASE_ID + 1)
        assertNotNull("First notification should exist", first)
        assertNotNull("Second notification should exist", second)
    }
}
