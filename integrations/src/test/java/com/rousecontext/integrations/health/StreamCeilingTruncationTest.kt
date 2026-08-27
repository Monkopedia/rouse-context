package com.rousecontext.integrations.health

import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.response.ReadRecordsResponse
import com.rousecontext.integrations.health.query.VitalsQueries
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A bucketed read runs under two ceilings that count different things: the
 * reader stops at [STREAM_MAX_RECORDS] *records*, the fold stops at
 * [MAX_RECORDS] *samples*. Which one binds depends on how many samples each
 * record yields — and whichever binds, the answer must say so, because buckets
 * that stopped early do not cover the range the caller asked about.
 *
 * These cases drive the real [HealthConnectClientRecordReader], the real
 * [VitalsQueries] mapping and the real fold against an endless supply, varying
 * only the samples-per-record yield, so the two ceilings are the only stopping
 * forces.
 */
class StreamCeilingTruncationTest {

    private val from: Instant = Instant.parse("2026-04-01T00:00:00Z")
    private val to: Instant = Instant.parse("2026-04-30T00:00:00Z")

    /** What a run cost and what it claimed. */
    private data class Measured(val pages: Int, val folded: Int, val truncated: Boolean)

    private fun heartRate(samples: Int): HeartRateRecord = HeartRateRecord(
        startTime = from,
        startZoneOffset = ZoneOffset.UTC,
        endTime = from.plusSeconds(samples.coerceAtLeast(1).toLong()),
        endZoneOffset = ZoneOffset.UTC,
        samples = List(samples) {
            HeartRateRecord.Sample(from.plusSeconds(it.toLong()), 60L)
        },
        metadata = Metadata.manualEntry(Device(type = Device.TYPE_PHONE))
    )

    /**
     * Bucket [supplyPages] pages of heart-rate records whose samples-per-record
     * yield repeats [pattern] (so `[1, 0]` is half a sample per record).
     * The default supply never runs out, leaving the ceilings as the only thing
     * that can stop the read.
     */
    private fun measure(pattern: List<Int>, supplyPages: Int = Int.MAX_VALUE): Measured =
        runBlocking {
            val distinct = pattern.distinct().associateWith { heartRate(it) }
            val page = List(READ_PAGE_SIZE) { i -> distinct.getValue(pattern[i % pattern.size]) }
            var calls = 0
            val reader = HealthConnectClientRecordReader(
                fetchPage = { request ->
                    calls++
                    @Suppress("UNCHECKED_CAST")
                    ReadRecordsResponse(
                        page.take(request.pageSize) as List<Nothing>,
                        if (calls < supplyPages) "more" else null
                    ) as ReadRecordsResponse<out Record>
                }
            )
            val repo = RealHealthConnectRepository(
                categoriesProvider = { listOf(VitalsQueries(reader)) },
                grantedPermissionsProvider = { emptySet() },
                historicalReadGrantedProvider = { false }
            )
            val result = repo.bucketRecords("HeartRate", from, to, Duration.ofHours(1))
            result as BucketResult.Success
            Measured(calls, result.totalCount, result.truncated)
        }

    @Test
    fun `dense records trip the sample cap within one page`() {
        val measured = measure(listOf(60))
        assertEquals("one page holds 60,000 samples", 1, measured.pages)
        assertEquals(MAX_RECORDS, measured.folded)
        assertTrue("the sample cap bound the fold", measured.truncated)
    }

    @Test
    fun `two samples per record trip the sample cap well inside the record ceiling`() {
        val measured = measure(listOf(2))
        assertEquals(26, measured.pages)
        assertEquals(MAX_RECORDS, measured.folded)
        assertTrue("the sample cap bound the fold", measured.truncated)
    }

    @Test
    fun `one sample per record still trips the sample cap at the exact boundary`() {
        // The boundary that makes STREAM_MAX_RECORDS' page of slack a measured
        // fact rather than an argument: at exactly one sample per record the
        // fold still receives its full MAX_RECORDS before the reader's own
        // ceiling can cut it short. Goes red if either constant drifts.
        val measured = measure(listOf(1))
        assertEquals(STREAM_MAX_RECORDS / READ_PAGE_SIZE, measured.pages)
        assertEquals("the slack page must carry the fold to its cap", MAX_RECORDS, measured.folded)
        assertTrue("the sample cap bound the fold", measured.truncated)
    }

    @Test
    fun `half a sample per record hits the record ceiling and still reports truncation`() {
        // The fold never reaches its sample cap: the reader stops first, on its
        // own ceiling. The range holds more than these buckets describe, so
        // reporting truncated=false would present a half answer as a whole one.
        val measured = measure(listOf(1, 0))
        assertEquals(STREAM_MAX_RECORDS / READ_PAGE_SIZE, measured.pages)
        assertEquals(STREAM_MAX_RECORDS / 2, measured.folded)
        assertTrue("the record ceiling bound the read, and must be reported", measured.truncated)
    }

    @Test
    fun `records yielding no samples hit the record ceiling and still report truncation`() {
        val measured = measure(listOf(0))
        assertEquals(STREAM_MAX_RECORDS / READ_PAGE_SIZE, measured.pages)
        assertEquals(0, measured.folded)
        assertTrue("the record ceiling bound the read, and must be reported", measured.truncated)
    }

    @Test
    fun `a range that simply runs out is not reported as truncated`() {
        // The other half of the claim: truncation must mean "a ceiling stopped
        // this", not "the answer was short".
        val measured = measure(listOf(1), supplyPages = 3)
        assertEquals(3, measured.pages)
        assertEquals(3 * READ_PAGE_SIZE, measured.folded)
        assertFalse("nothing cut this read short", measured.truncated)
    }

    @Test
    fun `a range of empty records that runs out is not reported as truncated`() {
        val measured = measure(listOf(0), supplyPages = 3)
        assertEquals(3, measured.pages)
        assertEquals(0, measured.folded)
        assertFalse(
            "zero samples because there were none, not because of a ceiling",
            measured.truncated
        )
    }
}
