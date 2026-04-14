package com.rousecontext.mcp.health.query

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WheelchairPushesRecord
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Power
import androidx.health.connect.client.units.Velocity
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityQueriesTest {

    private val from: Instant = Instant.parse("2026-04-01T00:00:00Z")
    private val to: Instant = Instant.parse("2026-04-30T00:00:00Z")

    private fun make(): Pair<ActivityQueries, FakeRecordReader> {
        val reader = FakeRecordReader()
        return ActivityQueries(reader) to reader
    }

    @Test
    fun `steps groups by day sums count and fills date field`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            StepsRecord::class,
            listOf(
                StepsRecord(
                    startTime = Instant.parse("2026-04-10T08:00:00Z"),
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = Instant.parse("2026-04-10T09:00:00Z"),
                    endZoneOffset = ZoneOffset.UTC,
                    count = 1000L,
                    metadata = testMetadata
                ),
                StepsRecord(
                    startTime = Instant.parse("2026-04-10T10:00:00Z"),
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = Instant.parse("2026-04-10T11:00:00Z"),
                    endZoneOffset = ZoneOffset.UTC,
                    count = 250L,
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("Steps", from, to, null)
        assertEquals(1, result.size)
        val day = result[0]
        assertEquals("1250", day["count"]!!.jsonPrimitive.content)
        assertNotNull(day["start_time"])
        assertNotNull(day["end_time"])
        assertNotNull(day["date"])
    }

    @Test
    fun `active calories emits start_time end_time kcal`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            ActiveCaloriesBurnedRecord::class,
            listOf(
                ActiveCaloriesBurnedRecord(
                    startTime = Instant.parse("2026-04-10T08:00:00Z"),
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = Instant.parse("2026-04-10T09:00:00Z"),
                    endZoneOffset = ZoneOffset.UTC,
                    energy = Energy.kilocalories(500.0),
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("ActiveCaloriesBurned", from, to, null)
        assertEquals(1, result.size)
        assertEquals("500.0", result[0]["kcal"]!!.jsonPrimitive.content)
        assertNotNull(result[0]["start_time"])
        assertNotNull(result[0]["end_time"])
    }

    @Test
    fun `total calories emits start_time end_time kcal`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            TotalCaloriesBurnedRecord::class,
            listOf(
                TotalCaloriesBurnedRecord(
                    startTime = Instant.parse("2026-04-10T08:00:00Z"),
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = Instant.parse("2026-04-10T09:00:00Z"),
                    endZoneOffset = ZoneOffset.UTC,
                    energy = Energy.kilocalories(1800.0),
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("TotalCaloriesBurned", from, to, null)
        assertEquals("1800.0", result[0]["kcal"]!!.jsonPrimitive.content)
    }

    @Test
    fun `basal metabolic rate emits watts and kcal_per_day`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            BasalMetabolicRateRecord::class,
            listOf(
                BasalMetabolicRateRecord(
                    time = Instant.parse("2026-04-10T08:00:00Z"),
                    zoneOffset = ZoneOffset.UTC,
                    basalMetabolicRate = Power.watts(85.0),
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("BasalMetabolicRate", from, to, null)
        assertEquals(1, result.size)
        assertNotNull(result[0]["watts"])
        assertNotNull(result[0]["kcal_per_day"])
        assertNotNull(result[0]["time"])
    }

    @Test
    fun `distance emits meters`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            DistanceRecord::class,
            listOf(
                DistanceRecord(
                    startTime = Instant.parse("2026-04-10T08:00:00Z"),
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = Instant.parse("2026-04-10T09:00:00Z"),
                    endZoneOffset = ZoneOffset.UTC,
                    distance = Length.meters(5000.0),
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("Distance", from, to, null)
        assertEquals("5000.0", result[0]["meters"]!!.jsonPrimitive.content)
    }

    @Test
    fun `elevation gained emits meters`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            ElevationGainedRecord::class,
            listOf(
                ElevationGainedRecord(
                    startTime = Instant.parse("2026-04-10T08:00:00Z"),
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = Instant.parse("2026-04-10T09:00:00Z"),
                    endZoneOffset = ZoneOffset.UTC,
                    elevation = Length.meters(100.0),
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("ElevationGained", from, to, null)
        assertEquals("100.0", result[0]["meters"]!!.jsonPrimitive.content)
    }

    @Test
    fun `floors climbed emits floors count`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            FloorsClimbedRecord::class,
            listOf(
                FloorsClimbedRecord(
                    startTime = Instant.parse("2026-04-10T08:00:00Z"),
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = Instant.parse("2026-04-10T09:00:00Z"),
                    endZoneOffset = ZoneOffset.UTC,
                    floors = 12.0,
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("FloorsClimbed", from, to, null)
        assertEquals("12.0", result[0]["floors"]!!.jsonPrimitive.content)
    }

    @Test
    fun `exercise session emits exercise_type and optional title`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            ExerciseSessionRecord::class,
            listOf(
                ExerciseSessionRecord(
                    startTime = Instant.parse("2026-04-10T08:00:00Z"),
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = Instant.parse("2026-04-10T09:00:00Z"),
                    endZoneOffset = ZoneOffset.UTC,
                    exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
                    title = "Morning run",
                    notes = null,
                    metadata = testMetadata
                ),
                ExerciseSessionRecord(
                    startTime = Instant.parse("2026-04-11T08:00:00Z"),
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = Instant.parse("2026-04-11T09:00:00Z"),
                    endZoneOffset = ZoneOffset.UTC,
                    exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
                    title = null,
                    notes = null,
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("ExerciseSession", from, to, null)
        assertEquals(2, result.size)
        assertEquals("Morning run", result[0]["title"]!!.jsonPrimitive.content)
        assertNull(result[1]["title"])
    }

    @Test
    fun `speed flattens samples with meters_per_second`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            SpeedRecord::class,
            listOf(
                SpeedRecord(
                    startTime = Instant.parse("2026-04-10T08:00:00Z"),
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = Instant.parse("2026-04-10T09:00:00Z"),
                    endZoneOffset = ZoneOffset.UTC,
                    samples = listOf(
                        SpeedRecord.Sample(
                            time = Instant.parse("2026-04-10T08:10:00Z"),
                            speed = Velocity.metersPerSecond(3.0)
                        ),
                        SpeedRecord.Sample(
                            time = Instant.parse("2026-04-10T08:20:00Z"),
                            speed = Velocity.metersPerSecond(4.0)
                        )
                    ),
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("Speed", from, to, null)
        assertEquals(2, result.size)
        assertEquals("3.0", result[0]["meters_per_second"]!!.jsonPrimitive.content)
        assertEquals("4.0", result[1]["meters_per_second"]!!.jsonPrimitive.content)
    }

    @Test
    fun `power flattens samples with watts`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            PowerRecord::class,
            listOf(
                PowerRecord(
                    startTime = Instant.parse("2026-04-10T08:00:00Z"),
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = Instant.parse("2026-04-10T09:00:00Z"),
                    endZoneOffset = ZoneOffset.UTC,
                    samples = listOf(
                        PowerRecord.Sample(
                            time = Instant.parse("2026-04-10T08:10:00Z"),
                            power = Power.watts(200.0)
                        )
                    ),
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("Power", from, to, null)
        assertEquals(1, result.size)
        assertEquals("200.0", result[0]["watts"]!!.jsonPrimitive.content)
    }

    @Test
    fun `cycling pedaling cadence flattens samples with rpm`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            CyclingPedalingCadenceRecord::class,
            listOf(
                CyclingPedalingCadenceRecord(
                    startTime = Instant.parse("2026-04-10T08:00:00Z"),
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = Instant.parse("2026-04-10T09:00:00Z"),
                    endZoneOffset = ZoneOffset.UTC,
                    samples = listOf(
                        CyclingPedalingCadenceRecord.Sample(
                            time = Instant.parse("2026-04-10T08:10:00Z"),
                            revolutionsPerMinute = 90.0
                        )
                    ),
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("CyclingPedalingCadence", from, to, null)
        assertEquals("90.0", result[0]["rpm"]!!.jsonPrimitive.content)
    }

    @Test
    fun `steps cadence flattens samples with steps_per_minute`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            StepsCadenceRecord::class,
            listOf(
                StepsCadenceRecord(
                    startTime = Instant.parse("2026-04-10T08:00:00Z"),
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = Instant.parse("2026-04-10T09:00:00Z"),
                    endZoneOffset = ZoneOffset.UTC,
                    samples = listOf(
                        StepsCadenceRecord.Sample(
                            time = Instant.parse("2026-04-10T08:10:00Z"),
                            rate = 120.0
                        )
                    ),
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("StepsCadence", from, to, null)
        assertEquals("120.0", result[0]["steps_per_minute"]!!.jsonPrimitive.content)
    }

    @Test
    fun `wheelchair pushes emits count`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            WheelchairPushesRecord::class,
            listOf(
                WheelchairPushesRecord(
                    startTime = Instant.parse("2026-04-10T08:00:00Z"),
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = Instant.parse("2026-04-10T09:00:00Z"),
                    endZoneOffset = ZoneOffset.UTC,
                    count = 42L,
                    metadata = testMetadata
                )
            )
        )
        val result = queries.query("WheelchairPushes", from, to, null)
        assertEquals("42", result[0]["count"]!!.jsonPrimitive.content)
    }

    @Test
    fun `query respects limit`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            DistanceRecord::class,
            (1..5).map { i ->
                DistanceRecord(
                    startTime = Instant.parse("2026-04-0${i}T08:00:00Z"),
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = Instant.parse("2026-04-0${i}T09:00:00Z"),
                    endZoneOffset = ZoneOffset.UTC,
                    distance = Length.meters(100.0 * i),
                    metadata = testMetadata
                )
            }
        )
        assertEquals(2, queries.query("Distance", from, to, 2).size)
        assertEquals(5, queries.query("Distance", from, to, null).size)
    }

    @Test
    fun `query forwards from and to to reader`() = runBlocking {
        val (queries, reader) = make()
        queries.query("Distance", from, to, null)
        assertEquals(1, reader.reads.size)
        assertEquals(DistanceRecord::class, reader.reads[0].type)
        assertEquals(from, reader.reads[0].from)
        assertEquals(to, reader.reads[0].to)
    }

    @Test
    fun `summary emits steps_total when Steps granted`() = runBlocking {
        val (queries, reader) = make()
        reader.put(
            StepsRecord::class,
            listOf(
                StepsRecord(
                    startTime = Instant.parse("2026-04-10T08:00:00Z"),
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = Instant.parse("2026-04-10T09:00:00Z"),
                    endZoneOffset = ZoneOffset.UTC,
                    count = 1000L,
                    metadata = testMetadata
                ),
                StepsRecord(
                    startTime = Instant.parse("2026-04-11T08:00:00Z"),
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = Instant.parse("2026-04-11T09:00:00Z"),
                    endZoneOffset = ZoneOffset.UTC,
                    count = 2000L,
                    metadata = testMetadata
                )
            )
        )
        val summary = queries.summary(from, to, setOf("Steps"))
        assertEquals("3000", summary["steps_total"]!!.jsonPrimitive.content)
    }

    @Test
    fun `summary omits steps_total when Steps not granted`() = runBlocking {
        val (queries, _) = make()
        val summary = queries.summary(from, to, emptySet())
        assertTrue(summary.isEmpty())
    }

    @Test
    fun `unsupported recordType throws`() = runBlocking {
        val (queries, _) = make()
        try {
            queries.query("Weight", from, to, null)
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun `recordTypes covers all 13 activity types`() {
        val (queries, _) = make()
        assertEquals(
            setOf(
                "Steps",
                "ActiveCaloriesBurned",
                "TotalCaloriesBurned",
                "BasalMetabolicRate",
                "Distance",
                "ElevationGained",
                "FloorsClimbed",
                "ExerciseSession",
                "Speed",
                "Power",
                "CyclingPedalingCadence",
                "StepsCadence",
                "WheelchairPushes"
            ),
            queries.recordTypes
        )
    }
}
