package com.rousecontext.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.rousecontext.api.NotificationSettingsProvider
import com.rousecontext.api.PostSessionMode
import com.rousecontext.api.R as ApiR
import com.rousecontext.mcp.core.ToolNameHumanizer
import com.rousecontext.mcp.core.UNKNOWN_CLIENT_LABEL
import com.rousecontext.notifications.audit.AuditDao
import com.rousecontext.notifications.audit.AuditEntry
import com.rousecontext.tunnel.TunnelState
import kotlinx.coroutines.flow.Flow

/**
 * Posts a session-end summary notification when a tunnel session ends.
 *
 * Observes [TunnelState] transitions. When the tunnel enters [TunnelState.ACTIVE]
 * for the first time in a connection cycle we capture the current high-watermark
 * id of the audit log; when streams drain back to [TunnelState.CONNECTED] we
 * query entries inserted after that id and post a notification per
 * [PostSessionMode]:
 *
 * - [PostSessionMode.SUMMARY]: one notification per distinct client label
 *   (#342 / #347 locked design). Title is
 *   `"${clientLabel} used ${count} ${tool|tools}"` and body lists up to 3
 *   unique humanized tool names via [joinToolNames].
 * - [PostSessionMode.SUPPRESS]: nothing.
 * - [PostSessionMode.EACH_USAGE]: nothing — per-tool-call notifications fire
 *   from [com.rousecontext.notifications.PerToolCallNotifier], which is driven
 *   by [com.rousecontext.notifications.audit.RoomAuditListener] and fires per
 *   event rather than at session end.
 *
 * ## Notification ids
 *
 * The summary notification id is derived from the client label's hash so that
 * multi-client sessions produce multiple independent notifications and repeat
 * sessions for the same client replace the previous notification. See
 * [idForClient] for the mapping.
 *
 * ## Tap / action routing
 *
 * - Tap: deep-links into the audit-history screen filtered to the session
 *   time window via [EXTRA_START_MILLIS] / [EXTRA_END_MILLIS].
 * - `Manage` action: if all entries in this client's partition came from a
 *   single integration, deep-links into that integration's manage screen
 *   via [EXTRA_INTEGRATION_ID]; otherwise routes to the home screen.
 *
 * ## Why post on ACTIVE -> CONNECTED (stream drain) rather than DISCONNECTED?
 *
 * Fix #100: The foreground service calls `stopSelf()` on DISCONNECTED, which
 * cancels its `lifecycleScope`. If we post on DISCONNECTED, the suspending DAO
 * query races against the scope cancellation and almost always loses — the
 * notification never appears in production. Posting on stream drain fires while
 * the service is still alive (the service only stops on full DISCONNECTED),
 * so the DB query runs to completion.
 *
 * ## Repeated drains in one connection cycle
 *
 * We post at most once per connection cycle (from DISCONNECTED to DISCONNECTED).
 * If an AI client reopens streams after a *posted* drain, we do NOT re-capture
 * a cursor and do NOT re-post. The cursor is re-armed only after the tunnel
 * fully disconnects and reconnects. This prevents notification spam when clients
 * churn streams and matches the user-visible notion of "one session".
 *
 * An *empty* drain — a stream that opened and closed without producing any
 * audit-tool-call rows (for example a probe client that calls only
 * `tools/list` or `resources/list`, which go to `mcp_request_entries` not
 * `audit_entries`) — does NOT burn the per-cycle gate. The next ACTIVE
 * transition re-arms the cursor so any real tool-call activity in later
 * streams still surfaces a summary. See issue #324 for the regression this
 * distinction closes.
 */
class SessionSummaryNotifier(
    private val context: Context,
    private val auditDao: AuditDao,
    private val settingsProvider: NotificationSettingsProvider,
    private val activityClass: Class<*>,
    private val extraStartMillis: String = EXTRA_START_MILLIS,
    private val extraEndMillis: String = EXTRA_END_MILLIS,
    private val extraIntegrationId: String = EXTRA_INTEGRATION_ID
) {

    /**
     * Observe tunnel state and post summary notifications on session end.
     * Suspends until the flow completes (i.e. for the lifetime of the caller).
     */
    suspend fun observe(states: Flow<TunnelState>) {
        var cursorId: Long? = null
        var sessionStartMillis: Long = 0
        var posted = false
        var previousState: TunnelState? = null

        states.collect { state ->
            when {
                // Capture cursor on first ACTIVE of a connection cycle.
                // Once we've posted for this cycle, don't re-arm until the
                // tunnel fully disconnects.
                state == TunnelState.ACTIVE && cursorId == null && !posted -> {
                    cursorId = auditDao.latestId() ?: 0L
                    sessionStartMillis = System.currentTimeMillis()
                }
                // Post on ACTIVE -> CONNECTED (all streams drained).
                // The foreground service is still alive at this point, so the
                // suspending DAO query below completes before stopSelf() cancels
                // our scope. See class docs for the rationale (fix #100).
                previousState == TunnelState.ACTIVE &&
                    state == TunnelState.CONNECTED &&
                    cursorId != null -> {
                    val startCursor = cursorId!!
                    val sessionEndMillis = System.currentTimeMillis()
                    val entries = auditDao.queryCreatedAfter(startCursor)
                    val didPost = postForMode(entries, sessionStartMillis, sessionEndMillis)
                    cursorId = null
                    // Only burn the per-cycle gate when we actually posted.
                    // An empty drain (no audit rows since the cursor) can
                    // happen when an AI client opens a probe stream that
                    // only calls tools/list or resources/list — those go to
                    // mcp_request_entries, not audit_entries. Burning the
                    // gate on an empty drain would silently drop summaries
                    // for any subsequent stream in the same connection
                    // cycle that DOES have tool calls. See issue #324.
                    if (didPost) {
                        posted = true
                    }
                }
                // Reset when the tunnel fully disconnects. Defensive: if we
                // went ACTIVE -> DISCONNECTED without a CONNECTED drain, we
                // drop the cursor rather than post (the service is tearing
                // down and our scope is about to be cancelled anyway).
                state == TunnelState.DISCONNECTED -> {
                    cursorId = null
                    posted = false
                }
            }
            previousState = state
        }
    }

    /**
     * Post a session-end notification for [entries] according to the user's
     * current [PostSessionMode] preference.
     *
     * Returns `true` when a notification was actually posted, `false`
     * otherwise. The caller uses this to decide whether to burn the
     * per-connection-cycle `posted` gate — see [observe]'s handling of the
     * ACTIVE → CONNECTED branch.
     */
    private suspend fun postForMode(
        entries: List<AuditEntry>,
        startMillis: Long,
        endMillis: Long
    ): Boolean {
        val mode = settingsProvider.settings().postSessionMode
        return when (mode) {
            PostSessionMode.SUPPRESS -> false
            PostSessionMode.EACH_USAGE -> false
            PostSessionMode.SUMMARY -> {
                if (entries.isEmpty()) {
                    false
                } else {
                    postSummary(entries, startMillis, endMillis)
                    true
                }
            }
        }
    }

    /**
     * Partition [entries] by [AuditEntry.clientLabel] and post one
     * notification per client. Null client labels fall back to the literal
     * `"Unknown"` for defensive rendering; real unknown-client rows written
     * by the #345 labeler carry `Unknown (#N)` strings verbatim.
     */
    private fun postSummary(entries: List<AuditEntry>, startMillis: Long, endMillis: Long) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val byClient = entries.groupBy { it.clientLabel ?: UNKNOWN_CLIENT_LABEL }
        byClient.forEach { (clientLabel, clientEntries) ->
            val notification = buildClientNotification(
                clientLabel = clientLabel,
                entries = clientEntries,
                startMillis = startMillis,
                endMillis = endMillis
            )
            manager.notify(idForClient(clientLabel), notification)
        }
    }

    private fun buildClientNotification(
        clientLabel: String,
        entries: List<AuditEntry>,
        startMillis: Long,
        endMillis: Long
    ): android.app.Notification {
        val count = entries.size
        val title = buildTitle(clientLabel, count)
        val body = joinToolNames(rankedHumanizedTools(entries))
        val notificationId = idForClient(clientLabel)
        val contentIntent = buildTapIntent(notificationId, startMillis, endMillis)
        val managePendingIntent = buildManageIntent(notificationId, entries)

        return NotificationCompat.Builder(
            context,
            NotificationChannels.SESSION_SUMMARY_CHANNEL_ID
        )
            .setSmallIcon(ApiR.drawable.ic_stat_rouse)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(
                ApiR.drawable.ic_stat_rouse,
                context.getString(ApiR.string.notification_action_manage),
                managePendingIntent
            )
            .build()
    }

    /** Title: `"${clientLabel} used ${count} ${tool|tools}"`. */
    private fun buildTitle(clientLabel: String, count: Int): String {
        val toolLabel = context.getString(
            if (count == 1) {
                ApiR.string.notification_session_summary_tool_one
            } else {
                ApiR.string.notification_session_summary_tool_many
            }
        )
        return context.getString(
            ApiR.string.notification_session_summary_title,
            clientLabel,
            count,
            toolLabel
        )
    }

    /**
     * Unique humanized tool names ordered by call-count descending, with
     * ties broken alphabetically on the raw (snake_case) tool name so the
     * output is deterministic.
     */
    private fun rankedHumanizedTools(entries: List<AuditEntry>): List<String> = entries
        .groupingBy { it.toolName }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { (name, _) ->
            runCatching { ToolNameHumanizer.humanize(name) }.getOrDefault(name)
        }

    private fun buildTapIntent(
        notificationId: Int,
        startMillis: Long,
        endMillis: Long
    ): PendingIntent = PendingIntent.getActivity(
        context,
        notificationId,
        Intent(context, activityClass).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = ACTION_OPEN_AUDIT_HISTORY
            putExtra(extraStartMillis, startMillis)
            putExtra(extraEndMillis, endMillis)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /**
     * Build the `Manage` action's [PendingIntent]. Routes to the integration
     * manage page when every call in [entries] came from a single integration;
     * otherwise drops the user at home where they can pick the integration
     * they care about.
     */
    private fun buildManageIntent(notificationId: Int, entries: List<AuditEntry>): PendingIntent {
        val distinctIntegrations = entries.map { it.provider }.distinct()
        val intent = Intent(context, activityClass).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (distinctIntegrations.size == 1) {
                action = ACTION_OPEN_INTEGRATION_MANAGE
                putExtra(extraIntegrationId, distinctIntegrations.first())
            } else {
                action = ACTION_OPEN_HOME
            }
        }
        return PendingIntent.getActivity(
            context,
            notificationId + MANAGE_REQUEST_CODE_OFFSET,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        /** Base for per-client summary notification ids (see [idForClient]). */
        const val BASE_NOTIFICATION_ID: Int = 6000

        /**
         * Reserve 10k consecutive ids after [BASE_NOTIFICATION_ID] for the
         * per-client hash bucket. Keeps us clear of [PerToolCallNotifier.BASE_ID]
         * at 7000 while still giving enough spread to avoid collisions on
         * typical client-label hashes.
         *
         * NOTE: collisions are not catastrophic — a collision just means two
         * distinct clients with hash-equal labels replace each other's
         * notifications. Given `String.hashCode()` and reasonable label
         * cardinality (<100 clients on a single device), this is acceptable
         * and matches the locked design's "stable hash of clientLabel" guidance.
         */
        private const val CLIENT_ID_BUCKET: Int = 10_000

        /** Offset added to the tap requestCode for the Manage action PendingIntent. */
        private const val MANAGE_REQUEST_CODE_OFFSET = 100_000

        /** Intent extra: session start timestamp (ms since epoch). */
        const val EXTRA_START_MILLIS = "rouse.session_start_millis"

        /** Intent extra: session end timestamp (ms since epoch). */
        const val EXTRA_END_MILLIS = "rouse.session_end_millis"

        /** Intent extra: integration id for the Manage action deep-link. */
        const val EXTRA_INTEGRATION_ID: String = "rouse.integration.manage_id"

        /** Intent action signalling the activity should open the audit-history screen. */
        const val ACTION_OPEN_AUDIT_HISTORY: String = "com.rousecontext.action.OPEN_AUDIT_HISTORY"

        /** Intent action signalling the activity should open an integration manage screen. */
        const val ACTION_OPEN_INTEGRATION_MANAGE: String =
            "com.rousecontext.action.OPEN_INTEGRATION_MANAGE"

        /** Intent action signalling the activity should open the home screen. */
        const val ACTION_OPEN_HOME: String = "com.rousecontext.action.OPEN_HOME"

        /**
         * Stable notification id derived from a client label. Used so multi-
         * client sessions produce distinct notifications and repeat sessions
         * for the same client replace their previous notification.
         */
        fun idForClient(clientLabel: String): Int =
            BASE_NOTIFICATION_ID + (clientLabel.hashCode().mod(CLIENT_ID_BUCKET))
    }
}
