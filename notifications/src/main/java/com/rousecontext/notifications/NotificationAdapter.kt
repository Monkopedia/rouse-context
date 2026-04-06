package com.rousecontext.notifications

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

/**
 * Translates [NotificationAction]s into Android [NotificationManager] calls.
 */
class NotificationAdapter(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)
    private var nextId = FIRST_DYNAMIC_ID

    /**
     * Execute a list of notification actions.
     */
    fun execute(actions: List<NotificationAction>) {
        actions.forEach { execute(it) }
    }

    /**
     * Execute a single notification action.
     */
    fun execute(action: NotificationAction) {
        when (action) {
            is NotificationAction.ShowForeground -> {
                val notification = createForegroundNotification(context, action.message)
                manager.notify(FOREGROUND_ID, notification)
            }

            is NotificationAction.PostSummary -> {
                val suffix =
                    if (action.toolCallCount == 1) "" else "s"
                val body =
                    "${action.toolCallCount} tool call$suffix processed"
                val notification = buildSessionNotification(
                    "Session Complete",
                    body
                )
                manager.notify(nextId(), notification)
            }

            is NotificationAction.PostWarning -> {
                val notification = buildSessionNotification("Warning", action.message)
                manager.notify(nextId(), notification)
            }

            is NotificationAction.PostError -> {
                val text = if (action.streamId != null) {
                    "Stream ${action.streamId}: ${action.message}"
                } else {
                    action.message
                }
                val notification = buildErrorNotification(text)
                manager.notify(nextId(), notification)
            }

            is NotificationAction.PostToolUsage -> {
                val notification = buildSessionNotification(
                    "Tool Call",
                    "${action.toolName} (${action.provider})"
                )
                manager.notify(nextId(), notification)
            }

            is NotificationAction.PostInfo -> {
                val notification = buildSessionNotification("Info", action.message)
                manager.notify(nextId(), notification)
            }

            is NotificationAction.PostAlert -> {
                val notification = buildAlertNotification(action.message)
                manager.notify(nextId(), notification)
            }
        }
    }

    private fun buildSessionNotification(title: String, text: String) =
        NotificationCompat.Builder(context, NotificationChannels.SESSION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

    private fun buildErrorNotification(text: String) =
        NotificationCompat.Builder(context, NotificationChannels.ERROR_CHANNEL_ID)
            .setContentTitle("Error")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

    private fun buildAlertNotification(text: String) =
        NotificationCompat.Builder(context, NotificationChannels.ALERT_CHANNEL_ID)
            .setContentTitle("Security Alert")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

    private fun nextId(): Int = nextId++

    companion object {
        const val FOREGROUND_ID = 1
        private const val FIRST_DYNAMIC_ID = 100
    }
}
