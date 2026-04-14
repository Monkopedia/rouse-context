package com.rousecontext.notifications

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.rousecontext.api.R as ApiR

/**
 * Posts security-check notifications.
 *
 * Notifications use stable, semantic ids so repeated posts of the same
 * (check, severity) pair replace rather than stack, and different (check, severity)
 * combinations don't clobber each other. This fixes the per-instance counter bug
 * in the original [com.rousecontext.work.SecurityCheckWorker] where every worker
 * run restarted the id at 200, silently overwriting prior notifications.
 *
 * Alerts use [NotificationChannels.ALERT_CHANNEL_ID] with HIGH priority; warnings
 * use [NotificationChannels.SESSION_CHANNEL_ID] with DEFAULT priority.
 */
open class SecurityCheckNotifier(private val context: Context) {

    /** The kind of security check whose result is being surfaced. */
    enum class SecurityCheck { SELF_CERT, CT_LOG }

    open fun postAlert(check: SecurityCheck, reason: String) {
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

    open fun postInfo(check: SecurityCheck, reason: String) {
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
        SecurityCheck.SELF_CERT -> NOTIFICATION_ID_SELF_CERT_ALERT
        SecurityCheck.CT_LOG -> NOTIFICATION_ID_CT_LOG_ALERT
    }

    private fun infoIdFor(check: SecurityCheck): Int = when (check) {
        SecurityCheck.SELF_CERT -> NOTIFICATION_ID_SELF_CERT_INFO
        SecurityCheck.CT_LOG -> NOTIFICATION_ID_CT_LOG_INFO
    }

    companion object {
        // Stable ids in the 4210-4213 range. Chosen to avoid collision with:
        //   ForegroundNotification (1), FgsLimitNotifier (4201), audit ids (>=1000).
        const val NOTIFICATION_ID_SELF_CERT_ALERT = 4210
        const val NOTIFICATION_ID_CT_LOG_ALERT = 4211
        const val NOTIFICATION_ID_SELF_CERT_INFO = 4212
        const val NOTIFICATION_ID_CT_LOG_INFO = 4213
    }
}
