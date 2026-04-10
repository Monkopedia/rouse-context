package com.rousecontext.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.rousecontext.api.R as ApiR

/**
 * Posts high-priority notifications for authorization requests.
 *
 * Each notification includes Approve and Deny actions that broadcast
 * to the configured [approveAction] and [denyAction].
 *
 * @param context Application context for building notifications.
 * @param receiverClass The BroadcastReceiver class that handles approve/deny actions.
 * @param approveAction The intent action string for approval.
 * @param denyAction The intent action string for denial.
 * @param activityClass The Activity class to open when the notification body is tapped.
 * @param extraDisplayCode The intent extra key for the display code.
 * @param extraNotificationId The intent extra key for the notification ID.
 */
class AuthRequestNotifier(
    private val context: Context,
    private val receiverClass: Class<*>,
    private val approveAction: String,
    private val denyAction: String,
    private val activityClass: Class<*>,
    private val extraDisplayCode: String = "display_code",
    private val extraNotificationId: String = "notification_id"
) {

    private var counter = 0

    /**
     * Post a notification for a new authorization request.
     *
     * @param displayCode The human-readable code shown to the user.
     * @param integration The integration name requesting authorization.
     */
    fun post(displayCode: String, integration: String) {
        val notificationId = BASE_ID + counter++

        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, activityClass).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val approveIntent = PendingIntent.getBroadcast(
            context,
            notificationId * 2,
            Intent(context, receiverClass).apply {
                action = approveAction
                putExtra(extraDisplayCode, displayCode)
                putExtra(extraNotificationId, notificationId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val denyPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId * 2 + 1,
            Intent(context, receiverClass).apply {
                action = denyAction
                putExtra(extraDisplayCode, displayCode)
                putExtra(extraNotificationId, notificationId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(
            context,
            NotificationChannels.AUTH_REQUEST_CHANNEL_ID
        )
            .setSmallIcon(ApiR.drawable.ic_stat_rouse)
            .setColor(0xFFea4335.toInt())
            .setContentTitle("Approval Required")
            .setContentText("Code: $displayCode - Tap to approve or deny")
            .setSubText(integration)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .addAction(0, "Approve", approveIntent)
            .addAction(0, "Deny", denyPendingIntent)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(notificationId, notification)
    }

    companion object {
        /** Notification ID offset for auth request notifications. */
        const val BASE_ID = 5000
    }
}
