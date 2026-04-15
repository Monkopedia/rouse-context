package com.rousecontext.integrations.health.query

import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyQueriesTest {

    private val from: Instant = Instant.parse("2026-04-01T00:00:00Z")
    private val to: Instant = Instant.parse("2026-04-30T00:00:00Z")

    private fun make(): Pair<BodyQueries, FakeRecordReader> {
        val reader = FakeRecordReader()
        return BodyQueries(reader) to reader
    }

    @Test
    fun `weight emits time and kg sorted by time`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            WeightRecord::class,
            listOf(
                WeightRecord(
                    time = Instant.parse("2026-04-12T08:00:00Z"),
                    zoneOffset = ZoneOffset.UTC,
                    weight = Mass.kilograms(72.5),
                    metadata = testMetadata
                ),
                WeightRecord(
                    time = Instant.parse("2026-04-10T08:00:00Z"),
                    zoneOffset = ZoneOffset.UTC,
                    weight = Mass.kilograms(70.0),
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("Weight", from, to, null)
        assertEquals(2, result.size)
        assertEquals("70.0", result[0]["kg"]!!.jsonPrimitive.content)
        assertEquals("72.5", result[1]["kg"]!!.jsonPrimitive.content)
    }

    @Test
    fun `height emits meters`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            HeightRecord::class,
            listOf(
                HeightRecord(
                    time = Instant.parse("2026-04-10T08:00:00Z"),
                    zoneOffset = ZoneOffset.UTC,
                    height = Length.meters(1.75),
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("Height", from, to, null)
        assertEquals("1.75", result[0]["meters"]!!.jsonPrimitive.content)
    }

    @Test
    fun `body fat emits percentage`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            BodyFatRecord::class,
            listOf(
                BodyFatRecord(
                    time = Instant.parse("2026-04-10T08:00:00Z"),
                    zoneOffset = ZoneOffset.UTC,
                    percentage = Percentage(18.5),
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("BodyFat", from, to, null)
        assertEquals("18.5", result[0]["percentage"]!!.jsonPrimitive.content)
    }

    @Test
    fun `bone mass emits kg`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            BoneMassRecord::class,
            listOf(
                BoneMassRecord(
                    time = Instant.parse("2026-04-10T08:00:00Z"),
                    zoneOffset = ZoneOffset.UTC,
                    mass = Mass.kilograms(2.9),
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("BoneMass", from, to, null)
        assertEquals("2.9", result[0]["kg"]!!.jsonPrimitive.content)
    }

    @Test
    fun `lean body mass emits kg`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            LeanBodyMassRecord::class,
            listOf(
                LeanBodyMassRecord(
                    time = Instant.parse("2026-04-10T08:00:00Z"),
                    zoneOffset = ZoneOffset.UTC,
                    mass = Mass.kilograms(58.0),
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("LeanBodyMass", from, to, null)
        assertEquals("58.0", result[0]["kg"]!!.jsonPrimitive.content)
    }

    @Test
    fun `vo2 max emits ml_per_min_per_kg and measurement_method`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            Vo2MaxRecord::class,
            listOf(
                Vo2MaxRecord(
                    time = Instant.parse("2026-04-10T08:00:00Z"),
                    zoneOffset = ZoneOffset.UTC,
                    vo2MillilitersPerMinuteKilogram = 45.5,
                    measurementMethod = Vo2MaxRecord.MEASUREMENT_METHOD_HEART_RATE_RATIO,
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("Vo2Max", from, to, null)
        assertEquals("45.5", result[0]["ml_per_min_per_kg"]!!.jsonPrimitive.content)
        assertNotNull(result[0]["measurement_method"])
    }

    @Test
    fun `query respects limit`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            WeightRecord::class,
            (1..5).map { i ->
                WeightRecord(
                    time = Instant.parse("2026-04-0${i}T08:00:00Z"),
                    zoneOffset = ZoneOffset.UTC,
                    weight = Mass.kilograms(70.0 + i),
                    metadata = testMetadata
                )
            }
        )
        assertEquals(2, queries.query("Weight", from, to, 2).size)
    }

    @Test
    fun `query forwards from and to to reader`() = runBlocking {
        val (queries, reader) = make()
        queries.query("Weight", from, to, null)
        assertEquals(from, reader.reads[0].from)
        assertEquals(to, reader.reads[0].to)
    }

    @Test
    fun `summary emits weight_latest_kg from latest sorted record`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            WeightRecord::class,
            listOf(
                WeightRecord(
                    time = Instant.parse("2026-04-12T08:00:00Z"),
                    zoneOffset = ZoneOffset.UTC,
                    weight = Mass.kilograms(72.5),
                    metadata = testMetadata
                ),
                WeightRecord(
                    time = Instant.parse("2026-04-10T08:00:00Z"),
                    zoneOffset = ZoneOffset.UTC,
                    weight = Mass.kilograms(70.0),
                    metadata = testMetadata
                )
            )
        )
        val summary = queries.summary(from, to, setOf("Weight"))
        assertEquals("72.5", summary["weight_latest_kg"]!!.jsonPrimitive.content)
    }

    @Test
    fun `summary omits weight_latest_kg when Weight not granted`() = runBlocking {
        val (queries, _) = make()
        val summary = queries.summary(from, to, emptySet())
        assertTrue(summary.isEmpty())
    }

    @Test
    fun `recordTypes covers all 6 body types`() {
        val (queries, _) = make()
        assertEquals(
            setOf("Weight", "Height", "BodyFat", "BoneMass", "LeanBodyMass", "Vo2Max"),
            queries.recordTypes
        )
    }
}
