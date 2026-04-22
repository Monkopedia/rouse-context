package com.rousecontext.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.rousecontext.api.NotificationSettingsProvider
import com.rousecontext.api.PostSessionMode
import com.rousecontext.api.R as ApiR
import com.rousecontext.mcp.core.ToolCallEvent
import com.rousecontext.mcp.core.ToolNameHumanizer
import com.rousecontext.notifications.audit.PerCallObserver

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
 * ## Locked copy (#347, subset of the #342 design)
 *
 *  - Title: `"${clientLabel} used ${humanized(toolName)}"` — e.g.
 *    `Claude used Get Steps` or `Unknown (#1) used Get Steps`.
 *  - Body: integration display name only (e.g. `Health Connect`). The
 *    notification shade renders the call timestamp natively via
 *    [NotificationCompat.Builder.setWhen] with the event time, so we do NOT
 *    append a `· HH:mm` suffix.
 *  - Tap: deep-links to the audit-history screen scrolled to this specific
 *    call via the [EXTRA_SCROLL_TO_CALL_ID] extra.
 *  - Action: `Manage` button routes to the tool's integration manage page
 *    via the [EXTRA_INTEGRATION_ID] extra.
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
        val humanizedTool = runCatching { ToolNameHumanizer.humanize(event.toolName) }
            .getOrDefault(event.toolName)

        val title = context.getString(
            ApiR.string.notification_tool_call_title,
            event.clientLabel,
            humanizedTool
        )

        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, activityClass).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                action = ACTION_OPEN_AUDIT_HISTORY
                // Make the PendingIntent unique per call so FLAG_UPDATE_CURRENT
                // doesn't fold different call ids into the same outstanding
                // intent (important for the action button's per-integration id).
                putExtra(EXTRA_SCROLL_TO_CALL_ID, notificationId.toLong())
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Manage → integration manage page for this tool's integration.
        val managePendingIntent = PendingIntent.getActivity(
            context,
            // Use notificationId + 1 as requestCode so the Manage action
            // gets its own PendingIntent slot distinct from the tap intent.
            notificationId + MANAGE_REQUEST_CODE_OFFSET,
            Intent(context, activityClass).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                action = ACTION_OPEN_INTEGRATION_MANAGE
                putExtra(EXTRA_INTEGRATION_ID, event.providerId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(
            context,
            NotificationChannels.SESSION_SUMMARY_CHANNEL_ID
        )
            .setSmallIcon(ApiR.drawable.ic_stat_rouse)
            .setContentTitle(title)
            .setContentText(displayName)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            // Render the call timestamp natively in the notification shade so
            // the body can be the integration display name only (no
            // "· HH:mm" suffix). See #342 / #347 locked design.
            .setShowWhen(true)
            .setWhen(event.timestamp)
            .addAction(
                ApiR.drawable.ic_stat_rouse,
                context.getString(ApiR.string.notification_action_manage),
                managePendingIntent
            )
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(notificationId, notification)
    }

    companion object {
        /** Base notification id offset for per-call notifications. */
        const val BASE_ID = 7000

        /** Offset added to the tap PendingIntent request code for the Manage action. */
        private const val MANAGE_REQUEST_CODE_OFFSET = 100_000

        /**
         * Intent action signalling the activity should deep-link into the
         * audit-history screen and scroll to the call identified by
         * [EXTRA_SCROLL_TO_CALL_ID].
         */
        const val ACTION_OPEN_AUDIT_HISTORY: String = "com.rousecontext.action.OPEN_AUDIT_HISTORY"

        /**
         * Intent action signalling the activity should deep-link into the
         * integration manage screen for the integration identified by
         * [EXTRA_INTEGRATION_ID].
         */
        const val ACTION_OPEN_INTEGRATION_MANAGE: String =
            "com.rousecontext.action.OPEN_INTEGRATION_MANAGE"

        /** Intent extra: audit entry id the audit screen should scroll to. */
        const val EXTRA_SCROLL_TO_CALL_ID: String = "rouse.audit.scroll_to_call_id"

        /** Intent extra: integration id the Manage button should deep-link to. */
        const val EXTRA_INTEGRATION_ID: String = "rouse.integration.manage_id"
    }
}
