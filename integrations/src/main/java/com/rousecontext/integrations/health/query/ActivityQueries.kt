package com.rousecontext.integrations.health.query

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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Activity-category record queries: steps, calories, distance, exercise, etc.
 */
class ActivityQueries(private val reader: RecordReader) : CategoryQueries {

    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    override val recordTypes: Set<String> = setOf(
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
    )

    @Suppress("CyclomaticComplexMethod")
    override suspend fun query(
        recordType: String,
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> = when (recordType) {
        "Steps" -> querySteps(from, to, limit)
        "ActiveCaloriesBurned" -> queryActiveCalories(from, to, limit)
        "TotalCaloriesBurned" -> queryTotalCalories(from, to, limit)
        "BasalMetabolicRate" -> queryBasalMetabolicRate(from, to, limit)
        "Distance" -> queryDistance(from, to, limit)
        "ElevationGained" -> queryElevationGained(from, to, limit)
        "FloorsClimbed" -> queryFloorsClimbed(from, to, limit)
        "ExerciseSession" -> queryExercise(from, to, limit)
        "Speed" -> querySpeed(from, to, limit)
        "Power" -> queryPower(from, to, limit)
        "CyclingPedalingCadence" -> queryCyclingPedalingCadence(from, to, limit)
        "StepsCadence" -> queryStepsCadence(from, to, limit)
        "WheelchairPushes" -> queryWheelchairPushes(from, to, limit)
        else -> throw IllegalArgumentException("Unsupported record type: $recordType")
    }

    override suspend fun summary(from: Instant, to: Instant, granted: Set<String>): JsonObject =
        buildJsonObject {
            if ("Steps" in granted) {
                val steps = querySteps(from, to, null)
                val total = steps.sumOf { it["count"]?.toString()?.toLongOrNull() ?: 0L }
                put("steps_total", total)
            }
        }

    private suspend fun querySteps(from: Instant, to: Instant, limit: Int?): List<JsonObject> {
        val records = reader.read(StepsRecord::class, from, to)
        return records
            .groupBy { it.startTime.atZone(ZoneId.systemDefault()).toLocalDate() }
            .map { (date, dayRecords) ->
                buildJsonObject {
                    put("start_time", dayRecords.minOf { it.startTime }.toString())
                    put("end_time", dayRecords.maxOf { it.endTime }.toString())
                    put("date", date.format(dateFormatter))
                    put("count", dayRecords.sumOf { it.count })
                }
            }
            .sortedBy { it["date"].toString() }
            .let { if (limit != null) it.take(limit) else it }
    }

    private suspend fun queryActiveCalories(
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> {
        val records = reader.read(ActiveCaloriesBurnedRecord::class, from, to)
        return records
            .map { record ->
                buildJsonObject {
                    put("start_time", record.startTime.toString())
                    put("end_time", record.endTime.toString())
                    put("kcal", record.energy.inKilocalories)
                }
            }
            .let { if (limit != null) it.take(limit) else it }
    }

    private suspend fun queryTotalCalories(
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> {
        val records = reader.read(TotalCaloriesBurnedRecord::class, from, to)
        return records
            .map { record ->
                buildJsonObject {
                    put("start_time", record.startTime.toString())
                    put("end_time", record.endTime.toString())
                    put("kcal", record.energy.inKilocalories)
                }
            }
            .let { if (limit != null) it.take(limit) else it }
    }

    private suspend fun queryBasalMetabolicRate(
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> {
        val records = reader.read(BasalMetabolicRateRecord::class, from, to)
        return records
            .map { record ->
                buildJsonObject {
                    put("time", record.time.toString())
                    put("watts", record.basalMetabolicRate.inWatts)
                    put("kcal_per_day", record.basalMetabolicRate.inKilocaloriesPerDay)
                }
            }
            .sortedBy { it["time"].toString() }
            .let { if (limit != null) it.take(limit) else it }
    }

    private suspend fun queryDistance(from: Instant, to: Instant, limit: Int?): List<JsonObject> {
        val records = reader.read(DistanceRecord::class, from, to)
        return records
            .map { record ->
                buildJsonObject {
                    put("start_time", record.startTime.toString())
                    put("end_time", record.endTime.toString())
                    put("meters", record.distance.inMeters)
                }
            }
            .let { if (limit != null) it.take(limit) else it }
    }

    private suspend fun queryElevationGained(
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> {
        val records = reader.read(ElevationGainedRecord::class, from, to)
        return records
            .map { record ->
                buildJsonObject {
                    put("start_time", record.startTime.toString())
                    put("end_time", record.endTime.toString())
                    put("meters", record.elevation.inMeters)
                }
            }
            .let { if (limit != null) it.take(limit) else it }
    }

    private suspend fun queryFloorsClimbed(
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> {
        val records = reader.read(FloorsClimbedRecord::class, from, to)
        return records
            .map { record ->
                buildJsonObject {
                    put("start_time", record.startTime.toString())
                    put("end_time", record.endTime.toString())
                    put("floors", record.floors)
                }
            }
            .let { if (limit != null) it.take(limit) else it }
    }

    private suspend fun queryExercise(from: Instant, to: Instant, limit: Int?): List<JsonObject> {
        val records = reader.read(ExerciseSessionRecord::class, from, to)
        return records
            .map { record ->
                buildJsonObject {
                    put("start_time", record.startTime.toString())
                    put("end_time", record.endTime.toString())
                    put("exercise_type", record.exerciseType)
                    record.title?.let { put("title", it) }
                }
            }
            .let { if (limit != null) it.take(limit) else it }
    }

    private suspend fun querySpeed(from: Instant, to: Instant, limit: Int?): List<JsonObject> {
        val records = reader.read(SpeedRecord::class, from, to)
        return records
            .flatMap { record ->
                record.samples.map { sample ->
                    buildJsonObject {
                        put("time", sample.time.toString())
                        put("meters_per_second", sample.speed.inMetersPerSecond)
                    }
                }
            }
            .sortedBy { it["time"].toString() }
            .let { if (limit != null) it.take(limit) else it }
    }

    private suspend fun queryPower(from: Instant, to: Instant, limit: Int?): List<JsonObject> {
        val records = reader.read(PowerRecord::class, from, to)
        return records
            .flatMap { record ->
                record.samples.map { sample ->
                    buildJsonObject {
                        put("time", sample.time.toString())
                        put("watts", sample.power.inWatts)
                    }
                }
            }
            .sortedBy { it["time"].toString() }
            .let { if (limit != null) it.take(limit) else it }
    }

    private suspend fun queryCyclingPedalingCadence(
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> {
        val records = reader.read(CyclingPedalingCadenceRecord::class, from, to)
        return records
            .flatMap { record ->
                record.samples.map { sample ->
                    buildJsonObject {
                        put("time", sample.time.toString())
                        put("rpm", sample.revolutionsPerMinute)
                    }
                }
            }
            .sortedBy { it["time"].toString() }
            .let { if (limit != null) it.take(limit) else it }
    }

    private suspend fun queryStepsCadence(
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> {
        val records = reader.read(StepsCadenceRecord::class, from, to)
        return records
            .flatMap { record ->
                record.samples.map { sample ->
                    buildJsonObject {
                        put("time", sample.time.toString())
                        put("steps_per_minute", sample.rate)
                    }
                }
            }
            .sortedBy { it["time"].toString() }
            .let { if (limit != null) it.take(limit) else it }
    }

    private suspend fun queryWheelchairPushes(
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> {
        val records = reader.read(WheelchairPushesRecord::class, from, to)
        return records
            .map { record ->
                buildJsonObject {
                    put("start_time", record.startTime.toString())
                    put("end_time", record.endTime.toString())
                    put("count", record.count)
                }
            }
            .let { if (limit != null) it.take(limit) else it }
    }
}
