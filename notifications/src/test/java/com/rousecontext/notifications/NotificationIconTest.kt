package com.rousecontext.notifications

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.api.R as ApiR
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * Verifies that every notification type uses [ApiR.drawable.ic_stat_rouse]
 * as the small icon, not a system drawable or placeholder.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationIconTest {

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
    fun `foreground notification uses ic_stat_rouse`() {
        val notification = createForegroundNotification(context, "Connected")
        assertEquals(ApiR.drawable.ic_stat_rouse, notification.smallIcon.resId)
    }

    @Test
    fun `ShowForeground action posts notification with ic_stat_rouse`() {
        adapter.execute(NotificationAction.ShowForeground("Active"))

        val shadow = Shadows.shadowOf(manager)
        val notification = shadow.getNotification(NotificationAdapter.FOREGROUND_ID)
        assertEquals(ApiR.drawable.ic_stat_rouse, notification.smallIcon.resId)
    }

    @Test
    fun `PostSummary notification uses ic_stat_rouse`() {
        adapter.execute(NotificationAction.PostSummary(toolCallCount = 5))

        val shadow = Shadows.shadowOf(manager)
        val notification = shadow.allNotifications.first()
        assertEquals(ApiR.drawable.ic_stat_rouse, notification.smallIcon.resId)
    }

    @Test
    fun `PostWarning notification uses ic_stat_rouse`() {
        adapter.execute(NotificationAction.PostWarning("Low battery"))

        val shadow = Shadows.shadowOf(manager)
        val notification = shadow.allNotifications.first()
        assertEquals(ApiR.drawable.ic_stat_rouse, notification.smallIcon.resId)
    }

    @Test
    fun `PostError notification uses ic_stat_rouse`() {
        adapter.execute(NotificationAction.PostError("Connection lost", streamId = null))

        val shadow = Shadows.shadowOf(manager)
        val notification = shadow.allNotifications.first()
        assertEquals(ApiR.drawable.ic_stat_rouse, notification.smallIcon.resId)
    }

    @Test
    fun `PostError with streamId notification uses ic_stat_rouse`() {
        adapter.execute(NotificationAction.PostError("TLS failed", streamId = 42))

        val shadow = Shadows.shadowOf(manager)
        val notification = shadow.allNotifications.first()
        assertEquals(ApiR.drawable.ic_stat_rouse, notification.smallIcon.resId)
    }

    @Test
    fun `PostToolUsage notification uses ic_stat_rouse`() {
        adapter.execute(NotificationAction.PostToolUsage("get_steps", "health"))

        val shadow = Shadows.shadowOf(manager)
        val notification = shadow.allNotifications.first()
        assertEquals(ApiR.drawable.ic_stat_rouse, notification.smallIcon.resId)
    }

    @Test
    fun `PostInfo notification uses ic_stat_rouse`() {
        adapter.execute(NotificationAction.PostInfo("Session started"))

        val shadow = Shadows.shadowOf(manager)
        val notification = shadow.allNotifications.first()
        assertEquals(ApiR.drawable.ic_stat_rouse, notification.smallIcon.resId)
    }

    @Test
    fun `PostAlert notification uses ic_stat_rouse`() {
        adapter.execute(NotificationAction.PostAlert("Suspicious activity detected"))

        val shadow = Shadows.shadowOf(manager)
        val notification = shadow.allNotifications.first()
        assertEquals(ApiR.drawable.ic_stat_rouse, notification.smallIcon.resId)
    }

    @Test
    fun `AuthRequestNotifier notification uses ic_stat_rouse`() {
        val notifier = AuthRequestNotifier(
            context = context,
            receiverClass = AuthRequestNotifierTest.TestReceiver::class.java,
            approveAction = "com.test.APPROVE",
            denyAction = "com.test.DENY",
            activityClass = AuthRequestNotifierTest.TestActivity::class.java
        )
        notifier.post("ABC-123", "health")

        val shadow = Shadows.shadowOf(manager)
        val notification = shadow.getNotification(AuthRequestNotifier.BASE_ID)
        assertEquals(ApiR.drawable.ic_stat_rouse, notification.smallIcon.resId)
    }
}
