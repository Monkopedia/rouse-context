package com.rousecontext.integrations.health.query

import androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi
import androidx.health.connect.client.records.MindfulnessSessionRecord
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalMindfulnessSessionApi::class)
class MindfulnessQueriesTest {

    private val from: Instant = Instant.parse("2026-04-01T00:00:00Z")
    private val to: Instant = Instant.parse("2026-04-30T00:00:00Z")

    private companion object {
        const val TYPE_MEDITATION =
            MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_MEDITATION
        const val TYPE_BREATHING =
            MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_BREATHING
    }

    private fun make(): Pair<MindfulnessQueries, FakeRecordReader> {
        val reader = FakeRecordReader()
        return MindfulnessQueries(reader) to reader
    }

    @Test
    fun `mindfulness session emits session_type and optional title notes`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            MindfulnessSessionRecord::class,
            listOf(
                MindfulnessSessionRecord(
                    startTime = Instant.parse("2026-04-10T08:00:00Z"),
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = Instant.parse("2026-04-10T08:15:00Z"),
                    endZoneOffset = ZoneOffset.UTC,
                    mindfulnessSessionType = TYPE_MEDITATION,
                    title = "Morning",
                    notes = "Calm",
                    metadata = testMetadata
                ),
                MindfulnessSessionRecord(
                    startTime = Instant.parse("2026-04-11T08:00:00Z"),
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = Instant.parse("2026-04-11T08:15:00Z"),
                    endZoneOffset = ZoneOffset.UTC,
                    mindfulnessSessionType = TYPE_BREATHING,
                    title = null,
                    notes = null,
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("MindfulnessSession", from, to, null)
        assertEquals(2, result.size)
        assertNotNull(result[0]["session_type"])
        assertEquals("Morning", result[0]["title"]!!.jsonPrimitive.content)
        assertEquals("Calm", result[0]["notes"]!!.jsonPrimitive.content)
        assertNull(result[1]["title"])
        assertNull(result[1]["notes"])
    }

    @Test
    fun `query respects limit`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            MindfulnessSessionRecord::class,
            (1..3).map { i ->
                MindfulnessSessionRecord(
                    startTime = Instant.parse("2026-04-0${i}T08:00:00Z"),
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = Instant.parse("2026-04-0${i}T08:15:00Z"),
                    endZoneOffset = ZoneOffset.UTC,
                    mindfulnessSessionType = TYPE_MEDITATION,
                    title = null,
                    notes = null,
                    metadata = testMetadata
                )
            }
        )
        assertEquals(1, queries.query("MindfulnessSession", from, to, 1).size)
    }

    @Test
    fun `query forwards from and to`() = runBlocking {
        val (queries, reader) = make()
        queries.query("MindfulnessSession", from, to, null)
        assertEquals(from, reader.reads[0].from)
        assertEquals(to, reader.reads[0].to)
    }

    @Test
    fun `summary contributes nothing`() = runBlocking {
        val (queries, _) = make()
        val summary = queries.summary(from, to, setOf("MindfulnessSession"))
        assertTrue(summary.isEmpty())
    }

    @Test
    fun `recordTypes is MindfulnessSession only`() {
        val (queries, _) = make()
        assertEquals(setOf("MindfulnessSession"), queries.recordTypes)
    }
}
