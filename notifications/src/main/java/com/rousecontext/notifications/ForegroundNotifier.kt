package com.rousecontext.notifications

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import com.rousecontext.api.R as ApiR

/**
 * Builds the ongoing foreground-service notification.
 *
 * This is the ONE notifier in the codebase that returns a [Notification] instead
 * of posting it — foreground-service notifications cannot be posted via
 * `NotificationManager.notify()`, they must be passed to
 * `startForeground(id, notification)`. Do not add additional `build()` methods to
 * other notifiers without similar justification.
 *
 * Every other notifier in `:notifications` exposes `postX(...)` methods that
 * build and post in a single call; see issue #125 for the convention.
 */
object ForegroundNotifier {
    fun build(context: Context, message: String = "Connected"): Notification =
        NotificationCompat.Builder(context, NotificationChannels.FOREGROUND_CHANNEL_ID)
            .setContentTitle("Rouse Context")
            .setContentText(message)
            .setSmallIcon(ApiR.drawable.ic_stat_rouse)
            .setColor(0xFF0a1628.toInt())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
}
