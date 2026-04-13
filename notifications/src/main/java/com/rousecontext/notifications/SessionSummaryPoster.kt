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
 * we capture the current high-watermark id of the audit log; when the tunnel
 * enters [TunnelState.DISCONNECTED] we query entries inserted after that id
 * and post a notification per [PostSessionMode]:
 *
 * - [PostSessionMode.SUMMARY]: one notification summarizing counts per provider.
 * - [PostSessionMode.SUPPRESS]: nothing.
 * - [PostSessionMode.EACH_USAGE]: nothing (per-tool-call notifications are a
 *   separate concern; see TODO below).
 *
 * The notification taps through to the audit history, optionally filtered to the
 * session's time window (handled via intent extras keyed on [EXTRA_START_MILLIS]
 * and [EXTRA_END_MILLIS]).
 *
 * TODO(#95): EachUsage mode is intentionally deferred here — per-entry posting
 * requires wrapping [com.rousecontext.notifications.audit.RoomAuditListener]
 * or tail-observing the DAO, which is more invasive than this initial wiring.
 */
class SessionSummaryPoster(
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

        states.collect { state ->
            when (state) {
                TunnelState.ACTIVE -> {
                    // Start-of-session snapshot. Only capture if we don't already
                    // have one — multiple streams can toggle ACTIVE/CONNECTED
                    // within a single logical session.
                    if (cursorId == null) {
                        cursorId = auditDao.latestId() ?: 0L
                        sessionStartMillis = System.currentTimeMillis()
                    }
                }
                TunnelState.DISCONNECTED -> {
                    val startCursor = cursorId
                    if (startCursor != null) {
                        val sessionEndMillis = System.currentTimeMillis()
                        val entries = auditDao.queryCreatedAfter(startCursor)
                        postForMode(entries, sessionStartMillis, sessionEndMillis)
                        cursorId = null
                    }
                }
                else -> {
                    // CONNECTING / CONNECTED / DISCONNECTING: no-op.
                }
            }
        }
    }

    private fun postForMode(entries: List<AuditEntry>, startMillis: Long, endMillis: Long) {
        val mode = settingsProvider.settings.postSessionMode
        when (mode) {
            PostSessionMode.SUPPRESS -> return
            PostSessionMode.EACH_USAGE -> return
            PostSessionMode.SUMMARY -> {
                if (entries.isEmpty()) return
                postSummary(entries, startMillis, endMillis)
            }
        }
    }

    private fun postSummary(entries: List<AuditEntry>, startMillis: Long, endMillis: Long) {
        val total = entries.size
        val perProvider = entries.groupingBy { it.provider }.eachCount()
        val providerCount = perProvider.size

        val title = if (providerCount == 1) {
            "$total tool ${plural(total, "call", "calls")}"
        } else {
            "$total tool ${plural(total, "call", "calls")} across " +
                "$providerCount ${plural(providerCount, "integration", "integrations")}"
        }

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
            .setContentIntent(contentIntent)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun plural(n: Int, one: String, many: String): String = if (n == 1) one else many

    companion object {
        /** Notification id used for the summary notification. */
        const val NOTIFICATION_ID = 6000

        /** Intent extra: session start timestamp (ms since epoch). */
        const val EXTRA_START_MILLIS = "rouse.session_start_millis"

        /** Intent extra: session end timestamp (ms since epoch). */
        const val EXTRA_END_MILLIS = "rouse.session_end_millis"
    }
}
