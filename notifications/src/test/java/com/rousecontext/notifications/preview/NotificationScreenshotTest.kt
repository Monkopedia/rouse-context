package com.rousecontext.notifications.preview

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.rousecontext.api.R as ApiR
import com.rousecontext.notifications.NotificationChannels
import com.rousecontext.notifications.ForegroundNotifier
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Captures Roborazzi screenshots of every notification variant the app produces.
 * Each notification is rendered via [NotificationCard] in both light and dark mode.
 *
 * Run with:
 * ```
 * JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
 *   ./gradlew :notifications:testDebugUnitTest \
 *   --tests "*.NotificationScreenshotTest" --no-daemon
 * ```
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w400dp-h800dp-xxhdpi")
class NotificationScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun captureLight(name: String, notification: Notification) {
        composeRule.setContent {
            NotificationCard(notification = notification, isDark = false)
        }
        composeRule.onRoot().captureRoboImage("screenshots/${name}_light.png")
    }

    private fun captureDark(name: String, notification: Notification) {
        composeRule.setContent {
            NotificationCard(notification = notification, isDark = true)
        }
        composeRule.onRoot().captureRoboImage("screenshots/${name}_dark.png")
    }

    // =========================================================================
    // Foreground Service (via ForegroundNotifier.build)
    // =========================================================================

    @Test
    fun foregroundConnectingLight() = captureLight(
        "01_foreground_connecting",
        ForegroundNotifier.build(context, "Connecting")
    )

    @Test
    fun foregroundConnectingDark() =
        captureDark("01_foreground_connecting", ForegroundNotifier.build(context, "Connecting"))

    @Test
    fun foregroundConnectedLight() =
        captureLight("02_foreground_connected", ForegroundNotifier.build(context, "Connected"))

    @Test
    fun foregroundConnectedDark() =
        captureDark("02_foreground_connected", ForegroundNotifier.build(context, "Connected"))

    @Test
    fun foregroundActiveSessionLight() = captureLight(
        "03_foreground_active_session",
        ForegroundNotifier.build(context, "Connected \u2022 2 active streams")
    )

    @Test
    fun foregroundActiveSessionDark() = captureDark(
        "03_foreground_active_session",
        ForegroundNotifier.build(context, "Connected \u2022 2 active streams")
    )

    @Test
    fun foregroundDisconnectingLight() = captureLight(
        "04_foreground_disconnecting",
        ForegroundNotifier.build(context, "Disconnecting")
    )

    @Test
    fun foregroundDisconnectingDark() = captureDark(
        "04_foreground_disconnecting",
        ForegroundNotifier.build(context, "Disconnecting")
    )

    @Test
    fun foregroundDisconnectedLight() = captureLight(
        "05_foreground_disconnected",
        ForegroundNotifier.build(context, "Disconnected")
    )

    @Test
    fun foregroundDisconnectedDark() = captureDark(
        "05_foreground_disconnected",
        ForegroundNotifier.build(context, "Disconnected")
    )

    // =========================================================================
    // Auth Request (via AuthRequestNotifier pattern)
    // =========================================================================

    @Test
    fun authRequestLight() = captureLight("06_auth_request", buildAuthRequestNotification())

    @Test
    fun authRequestDark() = captureDark("06_auth_request", buildAuthRequestNotification())

    // =========================================================================
    // Session Activity
    // =========================================================================

    @Test
    fun sessionCompleteLight() = captureLight(
        "07_session_complete",
        buildSessionNotification("Session Complete", "3 tool calls processed")
    )

    @Test
    fun sessionCompleteDark() = captureDark(
        "07_session_complete",
        buildSessionNotification("Session Complete", "3 tool calls processed")
    )

    @Test
    fun sessionToolCallLight() = captureLight(
        "08_session_tool_call",
        buildSessionNotification("Tool Call", "get_steps (health)")
    )

    @Test
    fun sessionToolCallDark() = captureDark(
        "08_session_tool_call",
        buildSessionNotification("Tool Call", "get_steps (health)")
    )

    @Test
    fun sessionWarningLight() = captureLight(
        "09_session_warning",
        buildSessionNotification("Warning", "Session ended with no tool calls")
    )

    @Test
    fun sessionWarningDark() = captureDark(
        "09_session_warning",
        buildSessionNotification("Warning", "Session ended with no tool calls")
    )

    @Test
    fun sessionInfoLight() = captureLight(
        "10_session_info",
        buildSessionNotification("Info", "Certificate renewal started")
    )

    @Test
    fun sessionInfoDark() = captureDark(
        "10_session_info",
        buildSessionNotification("Info", "Certificate renewal started")
    )

    // =========================================================================
    // Errors
    // =========================================================================

    @Test
    fun errorConnectionLight() = captureLight(
        "11_error_connection",
        buildErrorNotification("Connection lost: server unreachable")
    )

    @Test
    fun errorConnectionDark() = captureDark(
        "11_error_connection",
        buildErrorNotification("Connection lost: server unreachable")
    )

    @Test
    fun errorStreamLight() = captureLight(
        "12_error_stream",
        buildErrorNotification("Stream 3: unexpected EOF")
    )

    @Test
    fun errorStreamDark() = captureDark(
        "12_error_stream",
        buildErrorNotification("Stream 3: unexpected EOF")
    )

    @Test
    fun errorCertExpiredLight() = captureLight(
        "13_error_cert_expired",
        buildErrorNotification("Certificate expired")
    )

    @Test
    fun errorCertExpiredDark() = captureDark(
        "13_error_cert_expired",
        buildErrorNotification("Certificate expired")
    )

    @Test
    fun errorCertRenewalFailedLight() = captureLight(
        "14_error_cert_renewal_failed",
        buildErrorNotification("Certificate renewal failed: rate limited by Let's Encrypt")
    )

    @Test
    fun errorCertRenewalFailedDark() = captureDark(
        "14_error_cert_renewal_failed",
        buildErrorNotification("Certificate renewal failed: rate limited by Let's Encrypt")
    )

    // =========================================================================
    // Security Alerts
    // =========================================================================

    @Test
    fun alertSelfCertLight() = captureLight(
        "15_alert_self_cert",
        buildAlertNotification("Self-check detected unexpected certificate fingerprint")
    )

    @Test
    fun alertSelfCertDark() = captureDark(
        "15_alert_self_cert",
        buildAlertNotification("Self-check detected unexpected certificate fingerprint")
    )

    @Test
    fun alertCtLogLight() = captureLight(
        "16_alert_ct_log",
        buildAlertNotification("CT log found unauthorized certificate for your domain")
    )

    @Test
    fun alertCtLogDark() = captureDark(
        "16_alert_ct_log",
        buildAlertNotification("CT log found unauthorized certificate for your domain")
    )

    // =========================================================================
    // Outreach (AI-triggered notifications)
    // =========================================================================

    @Test
    fun outreachBasicLight() = captureLight(
        "17_outreach_basic",
        buildOutreachNotification("Hydration Reminder", "You haven't logged water in 3 hours")
    )

    @Test
    fun outreachBasicDark() = captureDark(
        "17_outreach_basic",
        buildOutreachNotification("Hydration Reminder", "You haven't logged water in 3 hours")
    )

    @Test
    fun outreachWithActionsLight() = captureLight(
        "18_outreach_with_actions",
        buildOutreachNotificationWithActions(
            "Weekly Summary Ready",
            "Your health insights for this week are available",
            listOf("VIEW REPORT", "DISMISS")
        )
    )

    @Test
    fun outreachWithActionsDark() = captureDark(
        "18_outreach_with_actions",
        buildOutreachNotificationWithActions(
            "Weekly Summary Ready",
            "Your health insights for this week are available",
            listOf("VIEW REPORT", "DISMISS")
        )
    )

    // =========================================================================
    // Notification builders (mirror production code)
    // =========================================================================

    private fun buildAuthRequestNotification(): Notification {
        val dummyIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, NotificationChannels.AUTH_REQUEST_CHANNEL_ID)
            .setSmallIcon(ApiR.drawable.ic_stat_rouse)
            .setColor(0xFFea4335.toInt())
            .setContentTitle("Approval Required")
            .setContentText("Code: AB3X-9K2F \u2014 Tap to approve or deny")
            .setSubText("Health Connect")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(dummyIntent)
            .addAction(0, "Approve", dummyIntent)
            .addAction(0, "Deny", dummyIntent)
            .build()
    }

    private fun buildSessionNotification(title: String, text: String): Notification =
        NotificationCompat.Builder(context, NotificationChannels.SESSION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(ApiR.drawable.ic_stat_rouse)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

    private fun buildErrorNotification(text: String): Notification =
        NotificationCompat.Builder(context, NotificationChannels.ERROR_CHANNEL_ID)
            .setContentTitle("Error")
            .setContentText(text)
            .setSmallIcon(ApiR.drawable.ic_stat_rouse)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

    private fun buildAlertNotification(text: String): Notification =
        NotificationCompat.Builder(context, NotificationChannels.ALERT_CHANNEL_ID)
            .setContentTitle("Security Alert")
            .setContentText(text)
            .setSmallIcon(ApiR.drawable.ic_stat_rouse)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

    private fun buildOutreachNotification(title: String, message: String): Notification =
        NotificationCompat.Builder(context, "outreach")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

    private fun buildOutreachNotificationWithActions(
        title: String,
        message: String,
        actionLabels: List<String>
    ): Notification {
        val dummyIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, "outreach")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        actionLabels.forEach { label ->
            builder.addAction(0, label, dummyIntent)
        }
        return builder.build()
    }
}
