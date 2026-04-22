package com.rousecontext.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import com.rousecontext.api.NotificationSettingsProvider
import com.rousecontext.api.PostSessionMode
import com.rousecontext.api.R as ApiR
import com.rousecontext.mcp.core.ToolCallEvent
import com.rousecontext.notifications.audit.PerCallObserver
import java.util.Date

/**
 * Posts a low-priority notification for each MCP tool call while the user has
 * selected [PostSessionMode.EACH_USAGE].
 *
 * Fires on every audit event recorded by
 * [com.rousecontext.notifications.audit.RoomAuditListener]. Gate-checks the
 * current [PostSessionMode] and no-ops for [PostSessionMode.SUMMARY] or
 * [PostSessionMode.SUPPRESS] — see [SessionSummaryNotifier] for the
 * session-end summary path.
 *
 * Implements [PerCallObserver] so it can be plugged into [RoomAuditListener]
 * directly without the listener depending on `android.app` types.
 *
 * ## Channel
 *
 * Reuses [NotificationChannels.SESSION_SUMMARY_CHANNEL_ID] (IMPORTANCE_LOW) so
 * the user sees these entries in the shade without a heads-up or sound.
 *
 * ## Grouping
 *
 * Per-call notifications intentionally do NOT set a group key. Android
 * visually collapses same-group children when there is no explicit group
 * summary notification, which hid most per-call notifications from the user
 * (issue #331). Dropping the group means each call appears as its own row.
 *
 * Android will still visually cluster all notifications from this app under a
 * single app header in the shade; that is Android's default behaviour for any
 * process that posts more than one notification and cannot be disabled.
 *
 * ## Tap target
 *
 * Tapping a notification opens [activityClass] (the app's main activity); the
 * activity is expected to route to the audit history. Per-call notifications do
 * not pre-filter the history screen because the user is likely to want the
 * broader session context when they investigate a single call.
 *
 * @param context Application context used for the NotificationManager.
 * @param settingsProvider Source for the current [PostSessionMode].
 * @param integrationDisplayNames Map from integration id -> human-readable name;
 *   falls back to the raw id when absent.
 * @param activityClass Activity to launch when the notification is tapped.
 * @param idCounter Persistent counter used to allocate unique notification ids
 *   across process cold starts. See [NotificationIdCounter] and issue #331.
 */
class PerToolCallNotifier(
    private val context: Context,
    private val settingsProvider: NotificationSettingsProvider,
    private val integrationDisplayNames: Map<String, String>,
    private val activityClass: Class<*>,
    private val idCounter: NotificationIdCounter
) : PerCallObserver {

    override suspend fun onToolCallRecorded(event: ToolCallEvent) {
        notifyIfEnabled(event)
    }

    /**
     * Post a notification for [event] if [PostSessionMode.EACH_USAGE] is active.
     * No-op otherwise.
     */
    suspend fun notifyIfEnabled(event: ToolCallEvent) {
        if (settingsProvider.settings().postSessionMode != PostSessionMode.EACH_USAGE) return

        val notificationId = BASE_ID + idCounter.next()
        val displayName = integrationDisplayNames[event.providerId] ?: event.providerId
        val timeText = DateFormat.getTimeFormat(context).format(Date(event.timestamp))

        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, activityClass).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(
            context,
            NotificationChannels.SESSION_SUMMARY_CHANNEL_ID
        )
            .setSmallIcon(ApiR.drawable.ic_stat_rouse)
            .setContentTitle(
                context.getString(ApiR.string.notification_tool_call_title, event.toolName)
            )
            .setContentText(
                context.getString(ApiR.string.notification_tool_call_text, displayName, timeText)
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(notificationId, notification)
    }

    companion object {
        /** Base notification id offset for per-call notifications. */
        const val BASE_ID = 7000
    }
}
