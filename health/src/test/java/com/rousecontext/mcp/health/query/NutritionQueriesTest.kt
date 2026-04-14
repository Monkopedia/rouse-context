package com.rousecontext.mcp.health.query

import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Volume
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionQueriesTest {

    private val from: Instant = Instant.parse("2026-04-01T00:00:00Z")
    private val to: Instant = Instant.parse("2026-04-30T00:00:00Z")

    private fun make(): Pair<NutritionQueries, FakeRecordReader> {
        val reader = FakeRecordReader()
        return NutritionQueries(reader) to reader
    }

    @Test
    fun `hydration emits liters`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            HydrationRecord::class,
            listOf(
                HydrationRecord(
                    startTime = Instant.parse("2026-04-10T08:00:00Z"),
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = Instant.parse("2026-04-10T09:00:00Z"),
                    endZoneOffset = ZoneOffset.UTC,
                    volume = Volume.liters(0.5),
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("Hydration", from, to, null)
        assertEquals("0.5", result[0]["liters"]!!.jsonPrimitive.content)
        assertNotNull(result[0]["start_time"])
        assertNotNull(result[0]["end_time"])
    }

    @Test
    fun `nutrition includes optional name kcal protein fat carbs when present`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            NutritionRecord::class,
            listOf(
                NutritionRecord(
                    startTime = Instant.parse("2026-04-10T12:00:00Z"),
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = Instant.parse("2026-04-10T12:30:00Z"),
                    endZoneOffset = ZoneOffset.UTC,
                    name = "Lunch",
                    energy = Energy.kilocalories(650.0),
                    protein = Mass.grams(35.0),
                    totalFat = Mass.grams(20.0),
                    totalCarbohydrate = Mass.grams(80.0),
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("Nutrition", from, to, null)
        assertEquals("Lunch", result[0]["name"]!!.jsonPrimitive.content)
        assertEquals("650.0", result[0]["kcal"]!!.jsonPrimitive.content)
        assertEquals("35.0", result[0]["protein_g"]!!.jsonPrimitive.content)
        assertEquals("20.0", result[0]["fat_g"]!!.jsonPrimitive.content)
        assertEquals("80.0", result[0]["carbs_g"]!!.jsonPrimitive.content)
    }

    @Test
    fun `nutrition omits optional fields when absent`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            NutritionRecord::class,
            listOf(
                NutritionRecord(
                    startTime = Instant.parse("2026-04-10T12:00:00Z"),
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = Instant.parse("2026-04-10T12:30:00Z"),
                    endZoneOffset = ZoneOffset.UTC,
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("Nutrition", from, to, null)
        assertNull(result[0]["name"])
        assertNull(result[0]["kcal"])
        assertNull(result[0]["protein_g"])
        assertNull(result[0]["fat_g"])
        assertNull(result[0]["carbs_g"])
        assertNotNull(result[0]["start_time"])
    }

    @Test
    fun `query respects limit`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            HydrationRecord::class,
            (1..4).map { i ->
                HydrationRecord(
                    startTime = Instant.parse("2026-04-0${i}T08:00:00Z"),
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = Instant.parse("2026-04-0${i}T09:00:00Z"),
                    endZoneOffset = ZoneOffset.UTC,
                    volume = Volume.liters(0.25 * i),
                    metadata = testMetadata
                )
            }
        )
        assertEquals(2, queries.query("Hydration", from, to, 2).size)
    }

    @Test
    fun `query forwards from and to`() = runBlocking {
        val (queries, reader) = make()
        queries.query("Hydration", from, to, null)
        assertEquals(from, reader.reads[0].from)
        assertEquals(to, reader.reads[0].to)
    }

    @Test
    fun `summary contributes nothing`() = runBlocking {
        val (queries, _) = make()
        val summaryGranted = queries.summary(from, to, setOf("Hydration", "Nutrition"))
        val summaryEmpty = queries.summary(from, to, emptySet())
        assertTrue(summaryGranted.isEmpty())
        assertTrue(summaryEmpty.isEmpty())
    }

    @Test
    fun `recordTypes is Hydration and Nutrition`() {
        val (queries, _) = make()
        assertEquals(setOf("Hydration", "Nutrition"), queries.recordTypes)
    }
}
