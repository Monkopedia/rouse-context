package com.rousecontext.notifications

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat

/**
 * Creates the ongoing foreground service notification.
 */
fun createForegroundNotification(context: Context, message: String = "Connected"): Notification =
    NotificationCompat.Builder(context, NotificationChannels.FOREGROUND_CHANNEL_ID)
        .setContentTitle("Rouse Context")
        .setContentText(message)
        .setSmallIcon(android.R.drawable.ic_menu_manage)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()
