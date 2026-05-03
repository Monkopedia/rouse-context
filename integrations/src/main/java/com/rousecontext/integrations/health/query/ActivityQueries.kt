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

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    override suspend fun query(
        recordType: String,
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> = when (recordType) {
        "Steps" -> querySteps(from, to, limit)
        "ActiveCaloriesBurned" -> reader.queryRecords(
            ActiveCaloriesBurnedRecord::class,
            from,
            to,
            limit
        ) { record ->
            listOf(
                buildJsonObject {
                    put("start_time", record.startTime.toString())
                    put("end_time", record.endTime.toString())
                    put("kcal", record.energy.inKilocalories)
                }
            )
        }
        "TotalCaloriesBurned" -> reader.queryRecords(
            TotalCaloriesBurnedRecord::class,
            from,
            to,
            limit
        ) { record ->
            listOf(
                buildJsonObject {
                    put("start_time", record.startTime.toString())
                    put("end_time", record.endTime.toString())
                    put("kcal", record.energy.inKilocalories)
                }
            )
        }
        "BasalMetabolicRate" -> reader.queryRecords(
            BasalMetabolicRateRecord::class,
            from,
            to,
            limit,
            sortByTime = true
        ) { record ->
            listOf(
                buildJsonObject {
                    put("time", record.time.toString())
                    put("watts", record.basalMetabolicRate.inWatts)
                    put("kcal_per_day", record.basalMetabolicRate.inKilocaloriesPerDay)
                }
            )
        }
        "Distance" -> reader.queryRecords(
            DistanceRecord::class,
            from,
            to,
            limit
        ) { record ->
            listOf(
                buildJsonObject {
                    put("start_time", record.startTime.toString())
                    put("end_time", record.endTime.toString())
                    put("meters", record.distance.inMeters)
                }
            )
        }
        "ElevationGained" -> reader.queryRecords(
            ElevationGainedRecord::class,
            from,
            to,
            limit
        ) { record ->
            listOf(
                buildJsonObject {
                    put("start_time", record.startTime.toString())
                    put("end_time", record.endTime.toString())
                    put("meters", record.elevation.inMeters)
                }
            )
        }
        "FloorsClimbed" -> reader.queryRecords(
            FloorsClimbedRecord::class,
            from,
            to,
            limit
        ) { record ->
            listOf(
                buildJsonObject {
                    put("start_time", record.startTime.toString())
                    put("end_time", record.endTime.toString())
                    put("floors", record.floors)
                }
            )
        }
        "ExerciseSession" -> reader.queryRecords(
            ExerciseSessionRecord::class,
            from,
            to,
            limit
        ) { record ->
            listOf(
                buildJsonObject {
                    put("start_time", record.startTime.toString())
                    put("end_time", record.endTime.toString())
                    put("exercise_type", record.exerciseType)
                    record.title?.let { put("title", it) }
                }
            )
        }
        "Speed" -> reader.queryRecords(
            SpeedRecord::class,
            from,
            to,
            limit,
            sortByTime = true
        ) { record ->
            record.samples.map { sample ->
                buildJsonObject {
                    put("time", sample.time.toString())
                    put("meters_per_second", sample.speed.inMetersPerSecond)
                }
            }
        }
        "Power" -> reader.queryRecords(
            PowerRecord::class,
            from,
            to,
            limit,
            sortByTime = true
        ) { record ->
            record.samples.map { sample ->
                buildJsonObject {
                    put("time", sample.time.toString())
                    put("watts", sample.power.inWatts)
                }
            }
        }
        "CyclingPedalingCadence" -> reader.queryRecords(
            CyclingPedalingCadenceRecord::class,
            from,
            to,
            limit,
            sortByTime = true
        ) { record ->
            record.samples.map { sample ->
                buildJsonObject {
                    put("time", sample.time.toString())
                    put("rpm", sample.revolutionsPerMinute)
                }
            }
        }
        "StepsCadence" -> reader.queryRecords(
            StepsCadenceRecord::class,
            from,
            to,
            limit,
            sortByTime = true
        ) { record ->
            record.samples.map { sample ->
                buildJsonObject {
                    put("time", sample.time.toString())
                    put("steps_per_minute", sample.rate)
                }
            }
        }
        "WheelchairPushes" -> reader.queryRecords(
            WheelchairPushesRecord::class,
            from,
            to,
            limit
        ) { record ->
            listOf(
                buildJsonObject {
                    put("start_time", record.startTime.toString())
                    put("end_time", record.endTime.toString())
                    put("count", record.count)
                }
            )
        }
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
}
