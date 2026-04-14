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
    const val FGS_LIMIT_CHANNEL_ID = "rouse_fgs_limit"

    /**
     * Create all notification channels. Safe to call multiple times;
     * existing channels are not modified. No-op below API 26.
     */
    fun createAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java)
        buildChannels().forEach { manager.createNotificationChannel(it) }
    }

    private fun buildChannels(): List<NotificationChannel> = listOf(
        channel(
            FOREGROUND_CHANNEL_ID,
            "Foreground Service",
            NotificationManager.IMPORTANCE_LOW,
            "Ongoing notification while an integration is active"
        ),
        channel(
            SESSION_CHANNEL_ID,
            "Session Activity",
            NotificationManager.IMPORTANCE_DEFAULT,
            "Session summaries and tool call notifications"
        ),
        channel(
            ERROR_CHANNEL_ID,
            "Errors",
            NotificationManager.IMPORTANCE_HIGH,
            "Connection and certificate errors"
        ),
        channel(
            ALERT_CHANNEL_ID,
            "Security Alerts",
            NotificationManager.IMPORTANCE_HIGH,
            "Security-related alerts requiring attention"
        ),
        channel(
            AUTH_REQUEST_CHANNEL_ID,
            "Authorization Requests",
            NotificationManager.IMPORTANCE_DEFAULT,
            "Approval requests for new AI clients"
        ),
        channel(
            SESSION_SUMMARY_CHANNEL_ID,
            "Session Summaries",
            NotificationManager.IMPORTANCE_LOW,
            "Summary of tool calls after an MCP session ends"
        ),
        channel(
            OUTREACH_LAUNCH_CHANNEL_ID,
            "Outreach Launch Requests",
            NotificationManager.IMPORTANCE_DEFAULT,
            "Tap-to-launch fallback when an AI client requests " +
                "opening an app or link and the app cannot launch it directly"
        ),
        channel(
            FGS_LIMIT_CHANNEL_ID,
            "Foreground Service Limit",
            NotificationManager.IMPORTANCE_HIGH,
            "Android 6-hour foreground service limit alerts"
        )
    )

    private fun channel(
        id: String,
        name: String,
        importance: Int,
        desc: String
    ): NotificationChannel = NotificationChannel(id, name, importance).apply {
        description = desc
    }
}
