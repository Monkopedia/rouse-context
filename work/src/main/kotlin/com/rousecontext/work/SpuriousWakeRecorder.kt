package com.rousecontext.work

import android.content.Context
import android.content.SharedPreferences

/**
 * Records wake-cycle outcomes so the app can surface a diagnostic when the relay
 * appears to be issuing spurious wakes (wakes without any AI client actually
 * opening an MCP stream).
 *
 * A "wake cycle" spans from entering the CONNECTED state to returning to
 * DISCONNECTED. It is "spurious" when no ACTIVE transition was observed during
 * the cycle, meaning a wake fired but no stream ever opened.
 *
 * The interface exists so [IdleTimeoutManager] can stay unit-testable without
 * pulling in Android's [SharedPreferences] type.
 */
interface SpuriousWakeRecorder {

    /**
     * Called exactly once per completed wake cycle.
     *
     * @param hadActiveStream true if at least one ACTIVE state was observed
     *   during the cycle; false for a spurious wake.
     */
    fun recordWakeCycle(hadActiveStream: Boolean)
}

/**
 * [SpuriousWakeRecorder] backed by [SharedPreferences]. Tracks:
 *
 * - [KEY_SPURIOUS_TOTAL]: lifetime count of spurious wakes
 * - [KEY_TOTAL]: lifetime count of all completed wake cycles
 * - [KEY_SPURIOUS_TIMESTAMPS]: comma-separated list of up to [MAX_TIMESTAMPS]
 *   recent spurious-wake timestamps (millis since epoch), used for
 *   rolling-24h counts. Plain text keeps the format trivially parseable in
 *   unit tests without pulling in `org.json` (which is Android-framework-only
 *   on the JVM classpath).
 *
 * All writes use [SharedPreferences.Editor.apply] (async) which is safe for
 * this observability use case.
 */
class SharedPreferencesSpuriousWakeRecorder(
    private val prefs: SharedPreferences,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : SpuriousWakeRecorder {

    override fun recordWakeCycle(hadActiveStream: Boolean) {
        val editor = prefs.edit()
        val total = prefs.getLong(KEY_TOTAL, 0L) + 1L
        editor.putLong(KEY_TOTAL, total)

        if (!hadActiveStream) {
            val spurious = prefs.getLong(KEY_SPURIOUS_TOTAL, 0L) + 1L
            editor.putLong(KEY_SPURIOUS_TOTAL, spurious)

            val now = clock()
            val existing = prefs.getString(KEY_SPURIOUS_TIMESTAMPS, null)
            val updated = appendTimestamp(existing, now)
            editor.putString(KEY_SPURIOUS_TIMESTAMPS, updated)
        }
        editor.apply()
    }

    companion object {
        const val PREFS_NAME = "com.rousecontext.work.IDLE_PREFS"
        const val KEY_SPURIOUS_TOTAL = "spurious_wake_count_total"
        const val KEY_TOTAL = "total_wake_count"
        const val KEY_SPURIOUS_TIMESTAMPS = "spurious_wake_timestamps"
        const val MAX_TIMESTAMPS = 64
        const val ROLLING_WINDOW_MS = 24L * 60L * 60L * 1000L

        fun create(context: Context): SharedPreferencesSpuriousWakeRecorder {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return SharedPreferencesSpuriousWakeRecorder(prefs)
        }

        /**
         * Append [timestamp] to the comma-separated [existing] list, keeping
         * at most [MAX_TIMESTAMPS] most-recent entries. Returns the updated
         * serialized list.
         */
        internal fun appendTimestamp(existing: String?, timestamp: Long): String {
            val current = parseTimestamps(existing)
            val next = (current + timestamp).takeLast(MAX_TIMESTAMPS)
            return next.joinToString(",")
        }

        /**
         * Count timestamps within the last [windowMs] (default 24h) from [now].
         * Intended for UI display.
         */
        fun countWithinWindow(
            serialized: String?,
            now: Long,
            windowMs: Long = ROLLING_WINDOW_MS
        ): Int {
            val threshold = now - windowMs
            return parseTimestamps(serialized).count { it >= threshold }
        }

        private fun parseTimestamps(serialized: String?): List<Long> {
            if (serialized.isNullOrEmpty()) return emptyList()
            return serialized.split(',').mapNotNull { part ->
                part.trim().toLongOrNull()
            }
        }
    }
}
