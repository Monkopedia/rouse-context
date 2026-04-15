package com.rousecontext.integrations.health.query

import androidx.health.connect.client.records.CervicalMucusRecord
import androidx.health.connect.client.records.IntermenstrualBleedingRecord
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.OvulationTestRecord
import androidx.health.connect.client.records.SexualActivityRecord
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReproductiveQueriesTest {

    private val from: Instant = Instant.parse("2026-04-01T00:00:00Z")
    private val to: Instant = Instant.parse("2026-04-30T00:00:00Z")

    private fun make(): Pair<ReproductiveQueries, FakeRecordReader> {
        val reader = FakeRecordReader()
        return ReproductiveQueries(reader) to reader
    }

    @Test
    fun `menstruation flow emits time and flow`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            MenstruationFlowRecord::class,
            listOf(
                MenstruationFlowRecord(
                    time = Instant.parse("2026-04-10T08:00:00Z"),
                    zoneOffset = ZoneOffset.UTC,
                    flow = MenstruationFlowRecord.FLOW_MEDIUM,
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("MenstruationFlow", from, to, null)
        assertNotNull(result[0]["flow"])
        assertNotNull(result[0]["time"])
    }

    @Test
    fun `menstruation period emits start_time and end_time`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            MenstruationPeriodRecord::class,
            listOf(
                MenstruationPeriodRecord(
                    startTime = Instant.parse("2026-04-10T00:00:00Z"),
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = Instant.parse("2026-04-14T00:00:00Z"),
                    endZoneOffset = ZoneOffset.UTC,
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("MenstruationPeriod", from, to, null)
        assertEquals(
            "2026-04-10T00:00:00Z",
            result[0]["start_time"]!!.jsonPrimitive.content
        )
        assertEquals(
            "2026-04-14T00:00:00Z",
            result[0]["end_time"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `cervical mucus emits appearance and sensation`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            CervicalMucusRecord::class,
            listOf(
                CervicalMucusRecord(
                    time = Instant.parse("2026-04-10T08:00:00Z"),
                    zoneOffset = ZoneOffset.UTC,
                    appearance = CervicalMucusRecord.APPEARANCE_DRY,
                    sensation = CervicalMucusRecord.SENSATION_LIGHT,
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("CervicalMucus", from, to, null)
        assertNotNull(result[0]["appearance"])
        assertNotNull(result[0]["sensation"])
    }

    @Test
    fun `ovulation test emits result`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            OvulationTestRecord::class,
            listOf(
                OvulationTestRecord(
                    time = Instant.parse("2026-04-10T08:00:00Z"),
                    zoneOffset = ZoneOffset.UTC,
                    result = OvulationTestRecord.RESULT_POSITIVE,
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("OvulationTest", from, to, null)
        assertNotNull(result[0]["result"])
    }

    @Test
    fun `intermenstrual bleeding emits time only`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            IntermenstrualBleedingRecord::class,
            listOf(
                IntermenstrualBleedingRecord(
                    time = Instant.parse("2026-04-10T08:00:00Z"),
                    zoneOffset = ZoneOffset.UTC,
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("IntermenstrualBleeding", from, to, null)
        assertEquals(
            "2026-04-10T08:00:00Z",
            result[0]["time"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `sexual activity emits protection_used`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            SexualActivityRecord::class,
            listOf(
                SexualActivityRecord(
                    time = Instant.parse("2026-04-10T08:00:00Z"),
                    zoneOffset = ZoneOffset.UTC,
                    protectionUsed = SexualActivityRecord.PROTECTION_USED_PROTECTED,
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("SexualActivity", from, to, null)
        assertNotNull(result[0]["protection_used"])
    }

    @Test
    fun `query respects limit`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            MenstruationFlowRecord::class,
            (1..4).map { i ->
                MenstruationFlowRecord(
                    time = Instant.parse("2026-04-0${i}T08:00:00Z"),
                    zoneOffset = ZoneOffset.UTC,
                    flow = MenstruationFlowRecord.FLOW_LIGHT,
                    metadata = testMetadata
                )
            }
        )
        assertEquals(2, queries.query("MenstruationFlow", from, to, 2).size)
    }

    @Test
    fun `query forwards from and to`() = runBlocking {
        val (queries, reader) = make()
        queries.query("MenstruationFlow", from, to, null)
        assertEquals(from, reader.reads[0].from)
        assertEquals(to, reader.reads[0].to)
    }

    @Test
    fun `summary contributes nothing`() = runBlocking {
        val (queries, _) = make()
        val summaryGranted =
            queries.summary(from, to, setOf("MenstruationFlow", "SexualActivity"))
        assertTrue(summaryGranted.isEmpty())
    }

    @Test
    fun `recordTypes covers all 6 reproductive types`() {
        val (queries, _) = make()
        assertEquals(
            setOf(
                "MenstruationFlow",
                "MenstruationPeriod",
                "CervicalMucus",
                "OvulationTest",
                "IntermenstrualBleeding",
                "SexualActivity"
            ),
            queries.recordTypes
        )
    }
}
