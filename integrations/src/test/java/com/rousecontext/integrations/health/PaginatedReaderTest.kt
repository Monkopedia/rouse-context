package com.rousecontext.integrations.health

import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ReadRecordsResponse
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the paginated read loop in [HealthConnectClientRecordReader] (the crash
 * fix for high-volume record types). Uses the [fetchPage] seam so no real
 * Health Connect client is needed.
 */
class PaginatedReaderTest {

    private val from: Instant = Instant.parse("2026-04-01T00:00:00Z")
    private val to: Instant = Instant.parse("2026-04-30T00:00:00Z")

    private fun record(i: Int): RestingHeartRateRecord = RestingHeartRateRecord(
        time = from.plusSeconds(i.toLong()),
        zoneOffset = ZoneOffset.UTC,
        beatsPerMinute = 60L,
        metadata = Metadata.manualEntry(Device(type = Device.TYPE_PHONE))
    )

    @Suppress("UNCHECKED_CAST")
    private fun pagedReader(
        pages: List<Pair<List<Record>, String?>>,
        onRequest: (ReadRecordsRequest<out Record>) -> Unit = {}
    ): HealthConnectClientRecordReader {
        var call = 0
        return HealthConnectClientRecordReader(
            fetchPage = { request ->
                onRequest(request)
                val (records, token) = pages[call]
                call++
                ReadRecordsResponse(records as List<Nothing>, token) as
                    ReadRecordsResponse<out Record>
            }
        )
    }

    @Test
    fun `read follows page tokens and accumulates all records`() = runBlocking {
        val page1 = (0 until 1000).map { record(it) }
        val page2 = (1000 until 1500).map { record(it) }
        val reader = pagedReader(
            listOf(
                page1 to "token-1",
                page2 to null
            )
        )
        val result = reader.read(RestingHeartRateRecord::class, from, to)
        assertEquals(1500, result.size)
    }

    @Test
    fun `read sets a bounded page size on the request`() = runBlocking {
        val requests = mutableListOf<ReadRecordsRequest<out Record>>()
        val reader = pagedReader(
            listOf((0 until 3).map { record(it) } to null),
            onRequest = { requests += it }
        )
        reader.read(RestingHeartRateRecord::class, from, to)
        assertEquals(READ_PAGE_SIZE, requests[0].pageSize)
    }

    @Test
    fun `read stops accumulating past MAX_RECORDS ceiling`() = runBlocking {
        // Each page is full-sized and always hands back a non-null token, so
        // without the ceiling this would loop unbounded.
        val fullPage = (0 until READ_PAGE_SIZE).map { record(it) }
        var call = 0
        val reader = HealthConnectClientRecordReader(
            fetchPage = {
                call++
                @Suppress("UNCHECKED_CAST")
                ReadRecordsResponse(fullPage as List<Nothing>, "more") as
                    ReadRecordsResponse<out Record>
            }
        )
        val result = reader.read(RestingHeartRateRecord::class, from, to)
        assertTrue("should stop at/above ceiling", result.size >= MAX_RECORDS)
        assertTrue("should not run away", result.size < MAX_RECORDS + READ_PAGE_SIZE)
    }
}
