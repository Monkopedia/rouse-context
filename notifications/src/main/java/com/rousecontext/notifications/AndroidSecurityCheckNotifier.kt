package com.rousecontext.notifications

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.rousecontext.api.R as ApiR
import com.rousecontext.notifications.SecurityCheckNotifier.SecurityCheck

/**
 * Android-backed [SecurityCheckNotifier] implementation that posts real notifications
 * via the system [NotificationManager].
 */
class AndroidSecurityCheckNotifier(private val context: Context) : SecurityCheckNotifier {

    override fun postAlert(check: SecurityCheck, reason: String) {
        val notification = NotificationCompat
            .Builder(context, NotificationChannels.ALERT_CHANNEL_ID)
            .setContentTitle("Security Alert")
            .setContentText("${titleFor(check)}: $reason")
            .setSmallIcon(ApiR.drawable.ic_stat_rouse)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager().notify(alertIdFor(check), notification)
    }

    override fun postInfo(check: SecurityCheck, reason: String) {
        val notification = NotificationCompat
            .Builder(context, NotificationChannels.SESSION_CHANNEL_ID)
            .setContentTitle("Security Check")
            .setContentText("${titleFor(check)}: $reason")
            .setSmallIcon(ApiR.drawable.ic_stat_rouse)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager().notify(infoIdFor(check), notification)
    }

    private fun notificationManager(): NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    private fun titleFor(check: SecurityCheck): String = when (check) {
        SecurityCheck.SELF_CERT -> "Self-cert verification"
        SecurityCheck.CT_LOG -> "CT log check"
    }

    private fun alertIdFor(check: SecurityCheck): Int = when (check) {
        SecurityCheck.SELF_CERT -> SecurityCheckNotifier.NOTIFICATION_ID_SELF_CERT_ALERT
        SecurityCheck.CT_LOG -> SecurityCheckNotifier.NOTIFICATION_ID_CT_LOG_ALERT
    }

    private fun infoIdFor(check: SecurityCheck): Int = when (check) {
        SecurityCheck.SELF_CERT -> SecurityCheckNotifier.NOTIFICATION_ID_SELF_CERT_INFO
        SecurityCheck.CT_LOG -> SecurityCheckNotifier.NOTIFICATION_ID_CT_LOG_INFO
    }
}
