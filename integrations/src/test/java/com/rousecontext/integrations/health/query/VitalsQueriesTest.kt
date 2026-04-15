package com.rousecontext.integrations.health.query

import androidx.health.connect.client.records.BasalBodyTemperatureRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_ARMPIT
import androidx.health.connect.client.records.BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_MOUTH
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.units.BloodGlucose
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Pressure
import androidx.health.connect.client.units.Temperature
import androidx.health.connect.client.units.TemperatureDelta
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VitalsQueriesTest {

    private val from: Instant = Instant.parse("2026-04-01T00:00:00Z")
    private val to: Instant = Instant.parse("2026-04-30T00:00:00Z")

    private fun make(): Pair<VitalsQueries, FakeRecordReader> {
        val reader = FakeRecordReader()
        return VitalsQueries(reader) to reader
    }

    private companion object {
        const val WRIST_LOCATION = SkinTemperatureRecord.MEASUREMENT_LOCATION_WRIST
    }

    @Test
    fun `heart rate flattens samples with bpm sorted by time`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            HeartRateRecord::class,
            listOf(
                HeartRateRecord(
                    startTime = Instant.parse("2026-04-10T08:00:00Z"),
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = Instant.parse("2026-04-10T09:00:00Z"),
                    endZoneOffset = ZoneOffset.UTC,
                    samples = listOf(
                        HeartRateRecord.Sample(
                            time = Instant.parse("2026-04-10T08:10:00Z"),
                            beatsPerMinute = 70L
                        ),
                        HeartRateRecord.Sample(
                            time = Instant.parse("2026-04-10T08:20:00Z"),
                            beatsPerMinute = 75L
                        )
                    ),
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("HeartRate", from, to, null)
        assertEquals(2, result.size)
        assertEquals("70", result[0]["bpm"]!!.jsonPrimitive.content)
        assertEquals("75", result[1]["bpm"]!!.jsonPrimitive.content)
    }

    @Test
    fun `resting heart rate emits bpm`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            RestingHeartRateRecord::class,
            listOf(
                RestingHeartRateRecord(
                    time = Instant.parse("2026-04-10T08:00:00Z"),
                    zoneOffset = ZoneOffset.UTC,
                    beatsPerMinute = 58L,
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("RestingHeartRate", from, to, null)
        assertEquals("58", result[0]["bpm"]!!.jsonPrimitive.content)
    }

    @Test
    fun `hrv emits rmssd_ms`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            HeartRateVariabilityRmssdRecord::class,
            listOf(
                HeartRateVariabilityRmssdRecord(
                    time = Instant.parse("2026-04-10T08:00:00Z"),
                    zoneOffset = ZoneOffset.UTC,
                    heartRateVariabilityMillis = 45.2,
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("HeartRateVariabilityRmssd", from, to, null)
        assertEquals("45.2", result[0]["rmssd_ms"]!!.jsonPrimitive.content)
    }

    @Test
    fun `blood pressure emits systolic and diastolic`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            BloodPressureRecord::class,
            listOf(
                BloodPressureRecord(
                    time = Instant.parse("2026-04-10T08:00:00Z"),
                    zoneOffset = ZoneOffset.UTC,
                    systolic = Pressure.millimetersOfMercury(120.0),
                    diastolic = Pressure.millimetersOfMercury(80.0),
                    bodyPosition = BloodPressureRecord.BODY_POSITION_SITTING_DOWN,
                    measurementLocation = BloodPressureRecord.MEASUREMENT_LOCATION_LEFT_UPPER_ARM,
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("BloodPressure", from, to, null)
        assertEquals("120.0", result[0]["systolic"]!!.jsonPrimitive.content)
        assertEquals("80.0", result[0]["diastolic"]!!.jsonPrimitive.content)
    }

    @Test
    fun `blood glucose emits mmol_per_l`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            BloodGlucoseRecord::class,
            listOf(
                BloodGlucoseRecord(
                    time = Instant.parse("2026-04-10T08:00:00Z"),
                    zoneOffset = ZoneOffset.UTC,
                    level = BloodGlucose.millimolesPerLiter(5.4),
                    specimenSource = BloodGlucoseRecord.SPECIMEN_SOURCE_UNKNOWN,
                    mealType = BloodGlucoseRecord.RELATION_TO_MEAL_UNKNOWN,
                    relationToMeal = BloodGlucoseRecord.RELATION_TO_MEAL_UNKNOWN,
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("BloodGlucose", from, to, null)
        assertEquals("5.4", result[0]["mmol_per_l"]!!.jsonPrimitive.content)
    }

    @Test
    fun `oxygen saturation emits percentage`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            OxygenSaturationRecord::class,
            listOf(
                OxygenSaturationRecord(
                    time = Instant.parse("2026-04-10T08:00:00Z"),
                    zoneOffset = ZoneOffset.UTC,
                    percentage = Percentage(98.0),
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("OxygenSaturation", from, to, null)
        assertEquals("98.0", result[0]["percentage"]!!.jsonPrimitive.content)
    }

    @Test
    fun `respiratory rate emits breaths_per_minute`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            RespiratoryRateRecord::class,
            listOf(
                RespiratoryRateRecord(
                    time = Instant.parse("2026-04-10T08:00:00Z"),
                    zoneOffset = ZoneOffset.UTC,
                    rate = 16.0,
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("RespiratoryRate", from, to, null)
        assertEquals("16.0", result[0]["breaths_per_minute"]!!.jsonPrimitive.content)
    }

    @Test
    fun `body temperature emits celsius and measurement_location`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            BodyTemperatureRecord::class,
            listOf(
                BodyTemperatureRecord(
                    time = Instant.parse("2026-04-10T08:00:00Z"),
                    zoneOffset = ZoneOffset.UTC,
                    temperature = Temperature.celsius(37.0),
                    measurementLocation = MEASUREMENT_LOCATION_ARMPIT,
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("BodyTemperature", from, to, null)
        assertEquals("37.0", result[0]["celsius"]!!.jsonPrimitive.content)
        assertNotNull(result[0]["measurement_location"])
    }

    @Test
    fun `basal body temperature emits celsius`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            BasalBodyTemperatureRecord::class,
            listOf(
                BasalBodyTemperatureRecord(
                    time = Instant.parse("2026-04-10T08:00:00Z"),
                    zoneOffset = ZoneOffset.UTC,
                    temperature = Temperature.celsius(36.6),
                    measurementLocation = MEASUREMENT_LOCATION_MOUTH,
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("BasalBodyTemperature", from, to, null)
        assertEquals("36.6", result[0]["celsius"]!!.jsonPrimitive.content)
    }

    @Test
    fun `skin temperature emits baseline and deltas array`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            SkinTemperatureRecord::class,
            listOf(
                SkinTemperatureRecord(
                    startTime = Instant.parse("2026-04-10T22:00:00Z"),
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = Instant.parse("2026-04-11T06:00:00Z"),
                    endZoneOffset = ZoneOffset.UTC,
                    baseline = Temperature.celsius(34.0),
                    deltas = listOf(
                        SkinTemperatureRecord.Delta(
                            time = Instant.parse("2026-04-10T23:00:00Z"),
                            delta = TemperatureDelta.celsius(0.3)
                        )
                    ),
                    measurementLocation = WRIST_LOCATION,
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("SkinTemperature", from, to, null)
        assertEquals(1, result.size)
        assertEquals("34.0", result[0]["baseline_celsius"]!!.jsonPrimitive.content)
        val deltas = result[0]["deltas"]!!.jsonPrimitive.content
        assertTrue(deltas.contains("\"delta_celsius\":0.3"))
        assertTrue(deltas.startsWith("["))
    }

    @Test
    fun `query respects limit`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            RestingHeartRateRecord::class,
            (1..5).map { i ->
                RestingHeartRateRecord(
                    time = Instant.parse("2026-04-0${i}T08:00:00Z"),
                    zoneOffset = ZoneOffset.UTC,
                    beatsPerMinute = 55L + i,
                    metadata = testMetadata
                )
            }
        )
        assertEquals(3, queries.query("RestingHeartRate", from, to, 3).size)
    }

    @Test
    fun `query forwards from and to`() = runBlocking {
        val (queries, reader) = make()
        queries.query("RestingHeartRate", from, to, null)
        assertEquals(from, reader.reads[0].from)
        assertEquals(to, reader.reads[0].to)
    }

    @Test
    fun `summary averages heart rate bpm when granted`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            HeartRateRecord::class,
            listOf(
                HeartRateRecord(
                    startTime = Instant.parse("2026-04-10T08:00:00Z"),
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = Instant.parse("2026-04-10T09:00:00Z"),
                    endZoneOffset = ZoneOffset.UTC,
                    samples = listOf(
                        HeartRateRecord.Sample(Instant.parse("2026-04-10T08:10:00Z"), 60L),
                        HeartRateRecord.Sample(Instant.parse("2026-04-10T08:20:00Z"), 80L)
                    ),
                    metadata = testMetadata
                )
            )
        )
        val summary = queries.summary(from, to, setOf("HeartRate"))
        assertEquals(70.0, summary["avg_heart_rate"]!!.jsonPrimitive.content.toDouble(), 0.0001)
    }

    @Test
    fun `summary omits avg_heart_rate when not granted`() = runBlocking {
        val (queries, _) = make()
        val summary = queries.summary(from, to, emptySet())
        assertTrue(summary.isEmpty())
    }

    @Test
    fun `summary omits avg_heart_rate when no samples`() = runBlocking {
        val (queries, _) = make()
        val summary = queries.summary(from, to, setOf("HeartRate"))
        assertTrue(summary.isEmpty())
    }

    @Test
    fun `recordTypes covers all 10 vital types`() {
        val (queries, _) = make()
        assertEquals(10, queries.recordTypes.size)
    }
}
