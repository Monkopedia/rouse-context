package com.rousecontext.integrations.health.query

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BucketAggregationTest {

    private val from: Instant = Instant.parse("2026-04-01T00:00:00Z")

    @Test
    fun `bucketize groups by hour and computes count min max avg`() {
        val values = listOf(
            TimedValue(from.plusSeconds(60), 5.0),
            TimedValue(from.plusSeconds(120), 7.0),
            // second hour
            TimedValue(from.plusSeconds(3600 + 30), 10.0)
        )
        val buckets = bucketize(values, from, Duration.ofHours(1))
        assertEquals(2, buckets.size)

        assertEquals(from, buckets[0].start)
        assertEquals(2, buckets[0].count)
        assertEquals(5.0, buckets[0].min, 0.0001)
        assertEquals(7.0, buckets[0].max, 0.0001)
        assertEquals(6.0, buckets[0].avg, 0.0001)

        assertEquals(from.plusSeconds(3600), buckets[1].start)
        assertEquals(1, buckets[1].count)
        assertEquals(10.0, buckets[1].avg, 0.0001)
    }

    @Test
    fun `bucketize skips empty buckets`() {
        val values = listOf(
            TimedValue(from.plusSeconds(30), 1.0),
            // gap: nothing in hours 1 and 2
            TimedValue(from.plusSeconds(3 * 3600 + 30), 2.0)
        )
        val buckets = bucketize(values, from, Duration.ofHours(1))
        assertEquals(2, buckets.size)
        assertEquals(from, buckets[0].start)
        assertEquals(from.plusSeconds(3 * 3600), buckets[1].start)
    }

    @Test
    fun `bucketize returns empty for no values`() {
        assertTrue(bucketize(emptyList(), from, Duration.ofHours(1)).isEmpty())
    }

    @Test
    fun `downsampleEvenly returns all when under cap`() {
        val items = (1..5).toList()
        assertEquals(items, downsampleEvenly(items, 10))
    }

    @Test
    fun `downsampleEvenly keeps first and last and spreads across range`() {
        val items = (0..100).toList()
        val sampled = downsampleEvenly(items, 5)
        assertEquals(5, sampled.size)
        assertEquals(0, sampled.first())
        assertEquals(100, sampled.last())
        // strictly increasing / spread
        assertTrue(sampled.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `downsampleEvenly with cap 1 keeps first`() {
        assertEquals(listOf(0), downsampleEvenly((0..9).toList(), 1))
    }
}
