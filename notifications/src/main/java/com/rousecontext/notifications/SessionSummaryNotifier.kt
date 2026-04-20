package com.rousecontext.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.rousecontext.api.NotificationSettingsProvider
import com.rousecontext.api.PostSessionMode
import com.rousecontext.api.R as ApiR
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
 * - [PostSessionMode.SUMMARY]: one notification summarizing counts per provider.
 * - [PostSessionMode.SUPPRESS]: nothing.
 * - [PostSessionMode.EACH_USAGE]: nothing — per-tool-call notifications fire
 *   from [com.rousecontext.notifications.PerToolCallNotifier], which is driven
 *   by [com.rousecontext.notifications.audit.RoomAuditListener] and fires per
 *   event rather than at session end.
 *
 * The notification taps through to the audit history, optionally filtered to the
 * session's time window (handled via intent extras keyed on [EXTRA_START_MILLIS]
 * and [EXTRA_END_MILLIS]).
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
    private val extraEndMillis: String = EXTRA_END_MILLIS
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

    private fun postSummary(entries: List<AuditEntry>, startMillis: Long, endMillis: Long) {
        val total = entries.size
        val perProvider = entries.groupingBy { it.provider }.eachCount()
        val providerCount = perProvider.size

        val title = buildSummaryTitle(total, providerCount)
        val breakdown = perProvider.entries
            .sortedByDescending { it.value }
            .joinToString(", ") { (provider, count) -> "$provider $count" }

        val contentIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, activityClass).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(extraStartMillis, startMillis)
                putExtra(extraEndMillis, endMillis)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(
            context,
            NotificationChannels.SESSION_SUMMARY_CHANNEL_ID
        )
            .setSmallIcon(ApiR.drawable.ic_stat_rouse)
            .setContentTitle(title)
            .setContentText(breakdown)
            .setStyle(NotificationCompat.BigTextStyle().bigText(breakdown))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildSummaryTitle(total: Int, providerCount: Int): String {
        val callsLabel = context.getString(
            if (total == 1) {
                ApiR.string.notification_session_summary_calls_one
            } else {
                ApiR.string.notification_session_summary_calls_many
            }
        )
        return if (providerCount == 1) {
            context.getString(
                ApiR.string.notification_session_summary_single_integration,
                total,
                callsLabel
            )
        } else {
            val integrationsLabel = context.getString(
                ApiR.string.notification_session_summary_integrations_many
            )
            context.getString(
                ApiR.string.notification_session_summary_multi_integration,
                total,
                callsLabel,
                providerCount,
                integrationsLabel
            )
        }
    }

    companion object {
        /** Notification id used for the summary notification. */
        const val NOTIFICATION_ID = 6000

        /** Intent extra: session start timestamp (ms since epoch). */
        const val EXTRA_START_MILLIS = "rouse.session_start_millis"

        /** Intent extra: session end timestamp (ms since epoch). */
        const val EXTRA_END_MILLIS = "rouse.session_end_millis"
    }
}
