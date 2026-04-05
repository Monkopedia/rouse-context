package com.rousecontext.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * Notification channel definitions and setup.
 */
object NotificationChannels {
    const val FOREGROUND_CHANNEL_ID = "rouse_foreground"
    const val SESSION_CHANNEL_ID = "rouse_session"
    const val ERROR_CHANNEL_ID = "rouse_error"
    const val ALERT_CHANNEL_ID = "rouse_alert"

    /**
     * Create all notification channels. Safe to call multiple times;
     * existing channels are not modified.
     */
    fun createAll(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)

        val channels = listOf(
            NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                "Foreground Service",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Ongoing notification while the MCP server is active"
            },
            NotificationChannel(
                SESSION_CHANNEL_ID,
                "Session Activity",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Session summaries and tool call notifications"
            },
            NotificationChannel(
                ERROR_CHANNEL_ID,
                "Errors",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Connection and certificate errors"
            },
            NotificationChannel(
                ALERT_CHANNEL_ID,
                "Security Alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Security-related alerts requiring attention"
            },
        )

        channels.forEach { manager.createNotificationChannel(it) }
    }
}
