package com.rousecontext.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * Notification channel definitions and setup.
 */
object NotificationChannels {
    const val FOREGROUND_CHANNEL_ID = "rouse_foreground"
    const val SESSION_CHANNEL_ID = "rouse_session"
    const val ERROR_CHANNEL_ID = "rouse_error"
    const val ALERT_CHANNEL_ID = "rouse_alert"
    const val AUTH_REQUEST_CHANNEL_ID = "rouse_auth_request"
    const val SESSION_SUMMARY_CHANNEL_ID = "rouse_session_summary"
    const val OUTREACH_LAUNCH_CHANNEL_ID = "rouse_outreach_launch"

    /**
     * Create all notification channels. Safe to call multiple times;
     * existing channels are not modified. No-op below API 26.
     */
    fun createAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java)

        val channels = listOf(
            NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                "Foreground Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing notification while an integration is active"
            },
            NotificationChannel(
                SESSION_CHANNEL_ID,
                "Session Activity",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Session summaries and tool call notifications"
            },
            NotificationChannel(
                ERROR_CHANNEL_ID,
                "Errors",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Connection and certificate errors"
            },
            NotificationChannel(
                ALERT_CHANNEL_ID,
                "Security Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Security-related alerts requiring attention"
            },
            NotificationChannel(
                AUTH_REQUEST_CHANNEL_ID,
                "Authorization Requests",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Approval requests for new AI clients"
            },
            NotificationChannel(
                SESSION_SUMMARY_CHANNEL_ID,
                "Session Summaries",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Summary of tool calls after an MCP session ends"
            },
            NotificationChannel(
                OUTREACH_LAUNCH_CHANNEL_ID,
                "Outreach Launch Requests",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Tap-to-launch fallback when an AI client requests " +
                    "opening an app or link and the app cannot launch it directly"
            }
        )

        channels.forEach { manager.createNotificationChannel(it) }
    }
}
