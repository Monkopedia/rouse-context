package com.rousecontext.work

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.spuriousWakeDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "spurious_wake")

/**
 * Data snapshot for spurious-wake counters. Fetched in one shot so callers
 * don't have to run four parallel flows that re-combine downstream.
 */
data class SpuriousWakeCounters(
    val total: Long,
    val spuriousTotal: Long,
    val serializedTimestamps: String?
)

/**
 * Typed DataStore accessor for the spurious-wake telemetry written by
 * [DataStoreSpuriousWakeRecorder] and read by the Settings surface.
 */
class SpuriousWakePreferences(private val context: Context) {

    private val dataStore get() = context.spuriousWakeDataStore

    suspend fun total(): Long = dataStore.data.first()[KEY_TOTAL] ?: 0L

    suspend fun spuriousTotal(): Long = dataStore.data.first()[KEY_SPURIOUS_TOTAL] ?: 0L

    suspend fun serializedTimestamps(): String? = dataStore.data.first()[KEY_SPURIOUS_TIMESTAMPS]

    fun observeCounters(): Flow<SpuriousWakeCounters> = dataStore.data.map { prefs ->
        SpuriousWakeCounters(
            total = prefs[KEY_TOTAL] ?: 0L,
            spuriousTotal = prefs[KEY_SPURIOUS_TOTAL] ?: 0L,
            serializedTimestamps = prefs[KEY_SPURIOUS_TIMESTAMPS]
        )
    }

    /**
     * Test-only helper: resets counters and the timestamp buffer. Not referenced
     * from production code; kept on the main accessor for simplicity.
     */
    internal suspend fun reset() {
        dataStore.edit { prefs ->
            prefs[KEY_TOTAL] = 0L
            prefs[KEY_SPURIOUS_TOTAL] = 0L
            prefs.remove(KEY_SPURIOUS_TIMESTAMPS)
        }
    }

    suspend fun recordWake(hadActiveStream: Boolean, now: Long) {
        dataStore.edit { prefs ->
            val total = (prefs[KEY_TOTAL] ?: 0L) + 1L
            prefs[KEY_TOTAL] = total

            if (!hadActiveStream) {
                val spurious = (prefs[KEY_SPURIOUS_TOTAL] ?: 0L) + 1L
                prefs[KEY_SPURIOUS_TOTAL] = spurious

                val updated = appendTimestamp(prefs[KEY_SPURIOUS_TIMESTAMPS], now)
                prefs[KEY_SPURIOUS_TIMESTAMPS] = updated
            }
        }
    }

    companion object {
        private val KEY_TOTAL = longPreferencesKey("total_wake_count")
        private val KEY_SPURIOUS_TOTAL = longPreferencesKey("spurious_wake_count_total")
        private val KEY_SPURIOUS_TIMESTAMPS = stringPreferencesKey("spurious_wake_timestamps")

        const val MAX_TIMESTAMPS = 64
        const val ROLLING_WINDOW_MS = 24L * 60L * 60L * 1000L

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
