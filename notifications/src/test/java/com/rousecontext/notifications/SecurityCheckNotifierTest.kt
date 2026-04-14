package com.rousecontext.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SecurityCheckNotifierTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private lateinit var notifier: SecurityCheckNotifier

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        NotificationChannels.createAll(context)
        notifier = SecurityCheckNotifier(context)
    }

    @Test
    fun `alert posts on ALERT channel with HIGH priority`() {
        notifier.postAlert(SecurityCheckNotifier.SecurityCheck.SELF_CERT, "fingerprint mismatch")

        val active = manager.activeNotifications.single()
        assertEquals(NotificationChannels.ALERT_CHANNEL_ID, active.notification.channelId)
        assertEquals(
            NotificationCompatPriority.HIGH,
            active.notification.priorityCompat()
        )
    }

    @Test
    fun `info posts on SESSION channel with DEFAULT priority`() {
        notifier.postInfo(SecurityCheckNotifier.SecurityCheck.CT_LOG, "could not reach CT log")

        val active = manager.activeNotifications.single()
        assertEquals(NotificationChannels.SESSION_CHANNEL_ID, active.notification.channelId)
        assertEquals(
            NotificationCompatPriority.DEFAULT,
            active.notification.priorityCompat()
        )
    }

    @Test
    fun `same-check same-severity alerts replace - consistent id across two calls`() {
        notifier.postAlert(SecurityCheckNotifier.SecurityCheck.SELF_CERT, "reason 1")
        notifier.postAlert(SecurityCheckNotifier.SecurityCheck.SELF_CERT, "reason 2")

        val active = manager.activeNotifications
        assertEquals(
            "Repeated alerts for the same check should replace, not stack",
            1,
            active.size
        )
        assertEquals(
            SecurityCheckNotifier.NOTIFICATION_ID_SELF_CERT_ALERT,
            active.single().id
        )
    }

    @Test
    fun `different-check alerts do not clobber - distinct ids`() {
        notifier.postAlert(SecurityCheckNotifier.SecurityCheck.SELF_CERT, "self cert bad")
        notifier.postAlert(SecurityCheckNotifier.SecurityCheck.CT_LOG, "unexpected issuer")

        val active = manager.activeNotifications
        assertEquals(
            "Alerts for distinct checks should not overwrite each other",
            2,
            active.size
        )
        val ids = active.map { it.id }.toSet()
        assertEquals(
            setOf(
                SecurityCheckNotifier.NOTIFICATION_ID_SELF_CERT_ALERT,
                SecurityCheckNotifier.NOTIFICATION_ID_CT_LOG_ALERT
            ),
            ids
        )
        assertNotEquals(
            SecurityCheckNotifier.NOTIFICATION_ID_SELF_CERT_ALERT,
            SecurityCheckNotifier.NOTIFICATION_ID_CT_LOG_ALERT
        )
    }

    @Test
    fun `alert and info for same check use distinct ids`() {
        notifier.postAlert(SecurityCheckNotifier.SecurityCheck.SELF_CERT, "alert reason")
        notifier.postInfo(SecurityCheckNotifier.SecurityCheck.SELF_CERT, "warning reason")

        val active = manager.activeNotifications
        assertEquals(2, active.size)
        val ids = active.map { it.id }.toSet()
        assertEquals(
            setOf(
                SecurityCheckNotifier.NOTIFICATION_ID_SELF_CERT_ALERT,
                SecurityCheckNotifier.NOTIFICATION_ID_SELF_CERT_INFO
            ),
            ids
        )
    }

    /** Lightweight priority comparison that maps the legacy priority int onto test labels. */
    private enum class NotificationCompatPriority { HIGH, DEFAULT, OTHER }

    private fun Notification.priorityCompat(): NotificationCompatPriority = when (priority) {
        Notification.PRIORITY_HIGH -> NotificationCompatPriority.HIGH
        Notification.PRIORITY_DEFAULT -> NotificationCompatPriority.DEFAULT
        else -> NotificationCompatPriority.OTHER
    }
}
