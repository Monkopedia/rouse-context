package com.rousecontext.mcp.health.query

import androidx.health.connect.client.records.SleepSessionRecord
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepQueriesTest {

    private val from: Instant = Instant.parse("2026-04-01T00:00:00Z")
    private val to: Instant = Instant.parse("2026-04-30T00:00:00Z")

    private fun make(): Pair<SleepQueries, FakeRecordReader> {
        val reader = FakeRecordReader()
        return SleepQueries(reader) to reader
    }

    @Test
    fun `sleep session emits start_time end_time and stages`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            SleepSessionRecord::class,
            listOf(
                SleepSessionRecord(
                    startTime = Instant.parse("2026-04-10T22:00:00Z"),
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = Instant.parse("2026-04-11T06:00:00Z"),
                    endZoneOffset = ZoneOffset.UTC,
                    title = null,
                    notes = null,
                    stages = listOf(
                        SleepSessionRecord.Stage(
                            startTime = Instant.parse("2026-04-10T22:00:00Z"),
                            endTime = Instant.parse("2026-04-10T23:00:00Z"),
                            stage = SleepSessionRecord.STAGE_TYPE_LIGHT
                        ),
                        SleepSessionRecord.Stage(
                            startTime = Instant.parse("2026-04-10T23:00:00Z"),
                            endTime = Instant.parse("2026-04-11T01:00:00Z"),
                            stage = SleepSessionRecord.STAGE_TYPE_DEEP
                        )
                    ),
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("SleepSession", from, to, null)
        assertEquals(1, result.size)
        assertEquals(
            "2026-04-10T22:00:00Z",
            result[0]["start_time"]!!.jsonPrimitive.content
        )
        assertEquals(
            "2026-04-11T06:00:00Z",
            result[0]["end_time"]!!.jsonPrimitive.content
        )
        val stages = result[0]["stages"]!!.jsonPrimitive.content
        assertTrue("stages should include light", stages.contains("\"stage\":\"light\""))
        assertTrue("stages should include deep", stages.contains("\"stage\":\"deep\""))
    }

    @Test
    fun `query respects limit`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            SleepSessionRecord::class,
            (1..3).map { i ->
                SleepSessionRecord(
                    startTime = Instant.parse("2026-04-0${i}T22:00:00Z"),
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = Instant.parse("2026-04-0${i + 1}T06:00:00Z"),
                    endZoneOffset = ZoneOffset.UTC,
                    title = null,
                    notes = null,
                    stages = emptyList(),
                    metadata = testMetadata
                )
            }
        )
        assertEquals(1, queries.query("SleepSession", from, to, 1).size)
        assertEquals(3, queries.query("SleepSession", from, to, null).size)
    }

    @Test
    fun `query forwards from and to to reader`() = runBlocking {
        val (queries, reader) = make()
        queries.query("SleepSession", from, to, null)
        assertEquals(from, reader.reads[0].from)
        assertEquals(to, reader.reads[0].to)
    }

    @Test
    fun `summary emits sleep_hours summing session durations when granted`() = runBlocking {
        val (queries, reader) = make()
        // Two sessions: 4h + 2h = 6h = 360 minutes
        reader.put(
            SleepSessionRecord::class,
            listOf(
                SleepSessionRecord(
                    startTime = Instant.parse("2026-04-10T22:00:00Z"),
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = Instant.parse("2026-04-11T02:00:00Z"),
                    endZoneOffset = ZoneOffset.UTC,
                    title = null,
                    notes = null,
                    stages = emptyList(),
                    metadata = testMetadata
                ),
                SleepSessionRecord(
                    startTime = Instant.parse("2026-04-11T03:00:00Z"),
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = Instant.parse("2026-04-11T05:00:00Z"),
                    endZoneOffset = ZoneOffset.UTC,
                    title = null,
                    notes = null,
                    stages = emptyList(),
                    metadata = testMetadata
                )
            )
        )
        val summary = queries.summary(from, to, setOf("SleepSession"))
        // 360 / 60.0 == 6.0
        assertNotNull(summary["sleep_hours"])
        assertEquals(6.0, summary["sleep_hours"]!!.jsonPrimitive.content.toDouble(), 0.0001)
    }

    @Test
    fun `summary omits sleep_hours when not granted`() = runBlocking {
        val (queries, _) = make()
        val summary = queries.summary(from, to, emptySet())
        assertTrue(summary.isEmpty())
    }

    @Test
    fun `recordTypes is SleepSession only`() {
        val (queries, _) = make()
        assertEquals(setOf("SleepSession"), queries.recordTypes)
    }
}
