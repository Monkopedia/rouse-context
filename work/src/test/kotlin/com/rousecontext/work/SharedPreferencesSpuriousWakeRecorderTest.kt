package com.rousecontext.work

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for the helpers on [SharedPreferencesSpuriousWakeRecorder].
 * The full SharedPreferences-backed class is exercised indirectly via
 * [IdleTimeoutTest] with a fake recorder; these tests cover the ring-buffer
 * trim logic and rolling-window filtering.
 */
class SharedPreferencesSpuriousWakeRecorderTest {

    @Test
    fun `appendTimestamp creates list from null`() {
        val result = SharedPreferencesSpuriousWakeRecorder.appendTimestamp(null, 100L)
        assertEquals("100", result)
    }

    @Test
    fun `appendTimestamp appends to existing list`() {
        val result = SharedPreferencesSpuriousWakeRecorder.appendTimestamp("100,200", 300L)
        assertEquals("100,200,300", result)
    }

    @Test
    fun `appendTimestamp recovers from corrupt values`() {
        val result = SharedPreferencesSpuriousWakeRecorder.appendTimestamp("not-a-number", 100L)
        assertEquals("100", result)
    }

    @Test
    fun `appendTimestamp trims to MAX_TIMESTAMPS`() {
        var serialized: String? = null
        repeat(SharedPreferencesSpuriousWakeRecorder.MAX_TIMESTAMPS) { i ->
            serialized = SharedPreferencesSpuriousWakeRecorder.appendTimestamp(
                serialized,
                i.toLong()
            )
        }
        val after = SharedPreferencesSpuriousWakeRecorder.appendTimestamp(serialized, 9999L)
        val parsed = after.split(',').map { it.toLong() }
        assertEquals(SharedPreferencesSpuriousWakeRecorder.MAX_TIMESTAMPS, parsed.size)
        // Oldest entry (0) should have been dropped, newest should be 9999.
        assertEquals(1L, parsed.first())
        assertEquals(9999L, parsed.last())
    }

    @Test
    fun `countWithinWindow returns zero for null`() {
        val count = SharedPreferencesSpuriousWakeRecorder.countWithinWindow(null, 1_000_000L)
        assertEquals(0, count)
    }

    @Test
    fun `countWithinWindow returns zero for corrupt values`() {
        val count = SharedPreferencesSpuriousWakeRecorder.countWithinWindow(
            "garbage",
            1_000_000L
        )
        assertEquals(0, count)
    }

    @Test
    fun `countWithinWindow counts entries within rolling 24h`() {
        val now = 100_000_000_000L
        val day = 24L * 60L * 60L * 1000L
        val serialized = "${now - 10L},${now - day / 2},${now - 2L * day},${now - 3L * day}"
        val count = SharedPreferencesSpuriousWakeRecorder.countWithinWindow(serialized, now)
        assertEquals(2, count)
    }
}
