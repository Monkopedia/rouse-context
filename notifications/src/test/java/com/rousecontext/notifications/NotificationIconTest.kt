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
 * Verifies that every production notifier uses [ApiR.drawable.ic_stat_rouse]
 * as the small icon, not a system drawable or placeholder.
 *
 * Covers each [com.rousecontext.notifications] notifier in one place so icon
 * regressions surface even if per-notifier tests drift.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationIconTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        NotificationChannels.createAll(context)
    }

    @Test
    fun `ForegroundNotifier build uses ic_stat_rouse`() {
        val notification = ForegroundNotifier.build(context, "Connected")
        assertEquals(ApiR.drawable.ic_stat_rouse, notification.smallIcon.resId)
    }

    @Test
    fun `FgsLimitNotifier postLimitReachedNotification uses ic_stat_rouse`() {
        FgsLimitNotifier.postLimitReachedNotification(context)
        val active = manager.activeNotifications.single()
        assertEquals(ApiR.drawable.ic_stat_rouse, active.notification.smallIcon.resId)
    }

    @Test
    fun `SecurityCheckNotifier postAlert uses ic_stat_rouse`() {
        val notifier = SecurityCheckNotifier(context)
        notifier.postAlert(SecurityCheckNotifier.SecurityCheck.SELF_CERT, "bad cert")

        val shadow = Shadows.shadowOf(manager)
        val notification = shadow.getNotification(
            SecurityCheckNotifier.NOTIFICATION_ID_SELF_CERT_ALERT
        )
        assertEquals(ApiR.drawable.ic_stat_rouse, notification.smallIcon.resId)
    }

    @Test
    fun `SecurityCheckNotifier postInfo uses ic_stat_rouse`() {
        val notifier = SecurityCheckNotifier(context)
        notifier.postInfo(SecurityCheckNotifier.SecurityCheck.CT_LOG, "ok")

        val shadow = Shadows.shadowOf(manager)
        val notification = shadow.getNotification(
            SecurityCheckNotifier.NOTIFICATION_ID_CT_LOG_INFO
        )
        assertEquals(ApiR.drawable.ic_stat_rouse, notification.smallIcon.resId)
    }

    @Test
    fun `AuthRequestNotifier post uses ic_stat_rouse`() {
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
