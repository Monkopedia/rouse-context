package com.rousecontext.notifications

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * Monotonic notification id allocator persisted in DataStore.
 *
 * Fixes issue #331: the previous in-memory [java.util.concurrent.atomic.AtomicInteger]
 * counter reset to 0 on every process cold start. Because this app is woken
 * from FCM on demand and frequently torn down between tool calls, two
 * notifications posted in separate processes both landed on id
 * [PerToolCallNotifier.BASE_ID] + 0 and the second overwrote the first.
 *
 * Each call to [next] reads the current value, writes back value+1 (wrapping
 * at [WRAP_AT]), and returns the read value. DataStore's [DataStore.edit]
 * guarantees atomicity across concurrent callers within a process, and the
 * write is durable on disk so subsequent processes continue the sequence.
 *
 * Wraparound is bounded at [WRAP_AT] (10,000) so ids stay within the
 * [PerToolCallNotifier.BASE_ID]..[PerToolCallNotifier.BASE_ID] + 9999 window
 * and never collide with other modules' notification id allocations. In
 * practice 10,000 per-call notifications between wraparounds is far more than
 * any realistic retention horizon — by the time we wrap, the earliest ids have
 * long since been dismissed or auto-cancelled.
 */
class NotificationIdCounter(private val dataStore: DataStore<Preferences>) {

    /**
     * Atomically read, increment, and return the next counter value.
     *
     * Returns the value BEFORE the increment, so a fresh counter returns 0
     * on the first call.
     */
    suspend fun next(): Int {
        var captured = 0
        dataStore.edit { prefs ->
            val current = prefs[COUNTER_KEY] ?: 0
            captured = current
            prefs[COUNTER_KEY] = (current + 1) % WRAP_AT
        }
        return captured
    }

    /**
     * Test-only: force the next [next] call to return [value]. Useful for
     * exercising the wraparound path without invoking [next] 10,000 times.
     */
    internal suspend fun seedForTest(value: Int) {
        dataStore.edit { prefs ->
            prefs[COUNTER_KEY] = value
        }
    }

    /**
     * Read the current counter value without advancing it. Intended for
     * debugging/diagnostics only; production code should call [next].
     */
    suspend fun peek(): Int = dataStore.data.first()[COUNTER_KEY] ?: 0

    companion object {
        /**
         * Id space per-call notifications are confined to. Matches the
         * range allocation in [PerToolCallNotifier].
         */
        const val WRAP_AT: Int = 10_000

        private val COUNTER_KEY = intPreferencesKey("per_tool_call_counter")
    }
}
