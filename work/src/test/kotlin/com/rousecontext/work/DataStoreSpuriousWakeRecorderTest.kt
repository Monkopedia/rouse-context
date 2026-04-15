package com.rousecontext.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pure-JVM tests for [SpuriousWakePreferences.Companion.appendTimestamp] /
 * [SpuriousWakePreferences.Companion.countWithinWindow] plus end-to-end tests
 * of [DataStoreSpuriousWakeRecorder] backed by a real DataStore.
 */
@RunWith(RobolectricTestRunner::class)
class DataStoreSpuriousWakeRecorderTest {

    private lateinit var prefs: SpuriousWakePreferences

    @Before
    fun setUp() = kotlinx.coroutines.runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        prefs = SpuriousWakePreferences(context)
        // Reset DataStore state between tests (JVM-level persistence across
        // test methods otherwise leaks counters).
        prefs.reset()
    }

    @Test
    fun `appendTimestamp creates list from null`() {
        assertEquals("100", SpuriousWakePreferences.appendTimestamp(null, 100L))
    }

    @Test
    fun `appendTimestamp appends to existing list`() {
        val result = SpuriousWakePreferences.appendTimestamp("100,200", 300L)
        assertEquals("100,200,300", result)
    }

    @Test
    fun `appendTimestamp recovers from corrupt values`() {
        val result = SpuriousWakePreferences.appendTimestamp("not-a-number", 100L)
        assertEquals("100", result)
    }

    @Test
    fun `appendTimestamp trims to MAX_TIMESTAMPS`() {
        var serialized: String? = null
        repeat(SpuriousWakePreferences.MAX_TIMESTAMPS) { i ->
            serialized = SpuriousWakePreferences.appendTimestamp(serialized, i.toLong())
        }
        val after = SpuriousWakePreferences.appendTimestamp(serialized, 9999L)
        val parsed = after.split(',').map { it.toLong() }
        assertEquals(SpuriousWakePreferences.MAX_TIMESTAMPS, parsed.size)
        // Oldest entry (0) dropped, newest is 9999.
        assertEquals(1L, parsed.first())
        assertEquals(9999L, parsed.last())
    }

    @Test
    fun `countWithinWindow returns zero for null`() {
        assertEquals(0, SpuriousWakePreferences.countWithinWindow(null, 1_000_000L))
    }

    @Test
    fun `countWithinWindow returns zero for corrupt values`() {
        val count = SpuriousWakePreferences.countWithinWindow("garbage", 1_000_000L)
        assertEquals(0, count)
    }

    @Test
    fun `countWithinWindow counts entries within rolling 24h`() {
        val now = 100_000_000_000L
        val day = 24L * 60L * 60L * 1000L
        val serialized = "${now - 10L},${now - day / 2},${now - 2L * day},${now - 3L * day}"
        val count = SpuriousWakePreferences.countWithinWindow(serialized, now)
        assertEquals(2, count)
    }

    @Test
    fun `recordWake increments total and spurious counts`() = runTest {
        val recorder = DataStoreSpuriousWakeRecorder(prefs, clock = { 1_000L })

        recorder.recordWakeCycle(hadActiveStream = false)
        assertEquals(1L, prefs.total())
        assertEquals(1L, prefs.spuriousTotal())

        recorder.recordWakeCycle(hadActiveStream = true)
        assertEquals(2L, prefs.total())
        assertEquals(1L, prefs.spuriousTotal())
    }

    @Test
    fun `recordWake appends timestamp for spurious cycles only`() = runTest {
        var now = 10L
        val recorder = DataStoreSpuriousWakeRecorder(prefs, clock = { now })

        recorder.recordWakeCycle(hadActiveStream = true)
        assertEquals(null, prefs.serializedTimestamps())

        now = 20L
        recorder.recordWakeCycle(hadActiveStream = false)
        assertEquals("20", prefs.serializedTimestamps())

        now = 30L
        recorder.recordWakeCycle(hadActiveStream = false)
        assertEquals("20,30", prefs.serializedTimestamps())
    }
}
