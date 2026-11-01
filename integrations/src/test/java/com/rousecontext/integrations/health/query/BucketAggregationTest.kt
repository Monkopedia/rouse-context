package com.rousecontext.integrations.health.query

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BucketAggregationTest {

    private val from: Instant = Instant.parse("2026-04-01T00:00:00Z")

    @Test
    fun `bucketize groups by hour and computes count min max avg`() = runBlocking {
        val values = listOf(
            TimedValue(from.plusSeconds(60), 5.0),
            TimedValue(from.plusSeconds(120), 7.0),
            // second hour
            TimedValue(from.plusSeconds(3600 + 30), 10.0)
        )
        val aggregated =
            bucketize(values.asFlow().streamed(), from, Duration.ofHours(1), maxValues = 100)
        assertEquals(3, aggregated.totalCount)
        val buckets = aggregated.buckets
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
    fun `bucketize skips empty buckets`() = runBlocking {
        val values = listOf(
            TimedValue(from.plusSeconds(30), 1.0),
            // gap: nothing in hours 1 and 2
            TimedValue(from.plusSeconds(3 * 3600 + 30), 2.0)
        )
        val buckets = bucketize(
            values.asFlow().streamed(),
            from,
            Duration.ofHours(1),
            maxValues = 100
        ).buckets
        assertEquals(2, buckets.size)
        assertEquals(from, buckets[0].start)
        assertEquals(from.plusSeconds(3 * 3600), buckets[1].start)
    }

    @Test
    fun `bucketize returns empty for no values`() = runBlocking {
        val aggregated =
            bucketize(
                emptyFlow<TimedValue>().streamed(),
                from,
                Duration.ofHours(1),
                maxValues = 100
            )
        assertTrue(aggregated.buckets.isEmpty())
        assertEquals(0, aggregated.totalCount)
    }

    @Test
    fun `bucketize stops at maxValues and reports truncation`() = runBlocking {
        val values = (0 until 100).map { TimedValue(from.plusSeconds(it * 3600L), it.toDouble()) }
        val aggregated =
            bucketize(values.asFlow().streamed(), from, Duration.ofHours(1), maxValues = 10)
        assertTrue("must not claim whole-range coverage", aggregated.truncated)
        assertEquals(10, aggregated.totalCount)
        assertEquals(10, aggregated.buckets.size)
        // the tail of the range is absent, which is exactly what truncated says
        assertEquals(from.plusSeconds(9 * 3600L), aggregated.buckets.last().start)
    }

    @Test
    fun `bucketize is not truncated when the values fit`() = runBlocking {
        val values = (0 until 10).map { TimedValue(from.plusSeconds(it * 3600L), it.toDouble()) }
        val aggregated =
            bucketize(values.asFlow().streamed(), from, Duration.ofHours(1), maxValues = 10)
        assertTrue("exactly at the ceiling is still complete", !aggregated.truncated)
        assertEquals(10, aggregated.totalCount)
    }

    @Test
    fun `spanningBucketWidth divides the range into at most the requested buckets`() {
        val to = from.plus(Duration.ofDays(30))
        val width = spanningBucketWidth(from, to, buckets = 100)
        assertTrue(
            "width must cover the range in <= 100 buckets",
            width.toMillis() * 100 >= Duration.between(from, to).toMillis()
        )
        assertTrue(
            "width must not be needlessly coarse",
            width.toMillis() * 101 >= Duration.between(from, to).toMillis()
        )
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

    @Test
    fun `bucketize reports truncation when upstream stopped at its own ceiling`() = runBlocking {
        // The fold's own cap is nowhere near tripping, so without the terminal
        // note this reads as a complete answer over one sample.
        val aggregated = bucketize(
            flowOf(
                Streamed.Value(TimedValue(from.plusSeconds(60), 5.0)),
                Streamed.CeilingReached
            ),
            from,
            Duration.ofHours(1),
            maxValues = 100
        )
        assertEquals(1, aggregated.totalCount)
        assertEquals(1, aggregated.buckets.size)
        assertTrue("upstream stopped short; the fold must pass that on", aggregated.truncated)
    }
}
