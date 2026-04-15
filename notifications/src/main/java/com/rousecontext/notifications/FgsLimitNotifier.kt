package com.rousecontext.notifications

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rousecontext.api.R as ApiR

/**
 * Handles the Android 6-hour daily foreground service (`dataSync`) time-limit
 * exhaustion case.
 *
 * When `startForeground` throws `ForegroundServiceStartNotAllowedException`
 * because the cumulative daily FGS budget is exhausted, we post a user-visible
 * notification so the user knows why wakes have stopped being served, and then
 * stop the service. There is no retry — the next FCM wake will naturally attempt
 * `startForeground` again, and when the 24-hour rolling window gives the budget
 * back, the service will start normally.
 *
 * A fixed notification id is used so repeated exhausted wakes update the same
 * notification rather than spamming the shade.
 */
object FgsLimitNotifier {
    /** Fixed id so repeated posts replace instead of stacking. */
    const val NOTIFICATION_ID = 4201

    fun postLimitReachedNotification(context: Context) {
        val title = context.getString(ApiR.string.notification_fgs_limit_title)
        val text = context.getString(ApiR.string.notification_fgs_limit_text)
        val notification = NotificationCompat
            .Builder(context, NotificationChannels.FGS_LIMIT_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(ApiR.drawable.ic_stat_rouse)
            .setColor(ContextCompat.getColor(context, ApiR.color.rouse_navy_dark))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }
}
