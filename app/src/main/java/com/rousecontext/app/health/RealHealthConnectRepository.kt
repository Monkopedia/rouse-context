package com.rousecontext.app.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.rousecontext.mcp.health.HEALTH_DATA_HISTORY_PERMISSION
import com.rousecontext.mcp.health.HealthConnectRepository
import com.rousecontext.mcp.health.HealthConnectUnavailableException
import com.rousecontext.mcp.health.RecordTypeRegistry
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.reflect.KClass
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private typealias QueryHandler =
    suspend (from: Instant, to: Instant, limit: Int?) -> List<JsonObject>

/**
 * Production [HealthConnectRepository] backed by the Health Connect SDK.
 *
 * Lives in `:app` because it needs an Android [Context] to obtain the
 * [HealthConnectClient] instance.
 */
class RealHealthConnectRepository(private val context: Context) : HealthConnectRepository {

    private val client: HealthConnectClient by lazy {
        val status = HealthConnectClient.getSdkStatus(context)
        if (status != HealthConnectClient.SDK_AVAILABLE) {
            throw HealthConnectUnavailableException()
        }
        HealthConnectClient.getOrCreate(context)
    }

    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    private val queryDispatchers: Map<String, QueryHandler> = mapOf(
        "Steps" to ::querySteps,
        "HeartRate" to ::queryHeartRate,
        "SleepSession" to ::querySleep,
        "Weight" to ::queryWeight,
        "BloodPressure" to ::queryBloodPressure,
        "ActiveCaloriesBurned" to ::queryActiveCalories,
        "Distance" to ::queryDistance,
        "ExerciseSession" to ::queryExercise,
        "Hydration" to ::queryHydration,
        "OxygenSaturation" to ::queryOxygenSaturation,
        "RespiratoryRate" to ::queryRespiratoryRate,
        "Nutrition" to ::queryNutrition
    )

    override suspend fun queryRecords(
        recordType: String,
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> {
        RecordTypeRegistry[recordType]
            ?: throw IllegalArgumentException("Unknown record type: $recordType")
        val handler = queryDispatchers[recordType]
            ?: throw IllegalArgumentException("No query handler for: $recordType")
        return handler(from, to, limit)
    }

    override suspend fun getGrantedPermissions(): Set<String> {
        val granted = client.permissionController.getGrantedPermissions()
        return RecordTypeRegistry.allTypes
            .filter { info -> granted.contains(info.readPermission) }
            .map { it.name }
            .toSet()
    }

    override suspend fun isHistoricalReadGranted(): Boolean {
        val granted = client.permissionController.getGrantedPermissions()
        return HEALTH_DATA_HISTORY_PERMISSION in granted
    }

    override suspend fun getSummary(from: Instant, to: Instant): JsonObject {
        val granted = getGrantedPermissions()
        return buildJsonObject {
            if ("Steps" in granted) {
                val steps = querySteps(from, to, null)
                val total = steps.sumOf { it["count"]?.toString()?.toLongOrNull() ?: 0L }
                put("steps_total", total)
            }
            if ("HeartRate" in granted) {
                val samples = queryHeartRate(from, to, null)
                if (samples.isNotEmpty()) {
                    val avg = samples.mapNotNull {
                        it["bpm"]?.toString()?.toLongOrNull()
                    }.average()
                    put("avg_heart_rate", avg)
                }
            }
            if ("SleepSession" in granted) {
                val sessions = querySleep(from, to, null)
                val totalMinutes = sessions.sumOf { session ->
                    val start = session["start_time"]?.toString()?.trim('"')
                    val end = session["end_time"]?.toString()?.trim('"')
                    if (start != null && end != null) {
                        try {
                            Duration.between(Instant.parse(start), Instant.parse(end)).toMinutes()
                        } catch (_: Exception) {
                            0L
                        }
                    } else {
                        0L
                    }
                }
                put("sleep_hours", totalMinutes / MINUTES_PER_HOUR)
            }
            if ("Weight" in granted) {
                val weights = queryWeight(from, to, null)
                val latest = weights.lastOrNull()
                if (latest != null) {
                    val kg = latest["kg"]?.toString()?.toDoubleOrNull()
                    if (kg != null) put("weight_latest_kg", kg)
                }
            }
        }
    }

    // --- Per-type query methods ---

    private suspend fun querySteps(from: Instant, to: Instant, limit: Int?): List<JsonObject> {
        val records = readRecords(StepsRecord::class, from, to)
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

    private suspend fun queryHeartRate(from: Instant, to: Instant, limit: Int?): List<JsonObject> {
        val records = readRecords(HeartRateRecord::class, from, to)
        return records
            .flatMap { record ->
                record.samples.map { sample ->
                    buildJsonObject {
                        put("time", sample.time.toString())
                        put("bpm", sample.beatsPerMinute)
                    }
                }
            }
            .sortedBy { it["time"].toString() }
            .let { if (limit != null) it.take(limit) else it }
    }

    private suspend fun querySleep(from: Instant, to: Instant, limit: Int?): List<JsonObject> {
        val records = readRecords(SleepSessionRecord::class, from, to)
        return records
            .map { record ->
                buildJsonObject {
                    put("start_time", record.startTime.toString())
                    put("end_time", record.endTime.toString())
                    put(
                        "stages",
                        record.stages.joinToString(",") { stage ->
                            """{"stage":"${mapSleepStage(stage.stage)}",""" +
                                """"start":"${stage.startTime}","end":"${stage.endTime}"}"""
                        }.let { "[$it]" }
                    )
                }
            }
            .let { if (limit != null) it.take(limit) else it }
    }

    private suspend fun queryWeight(from: Instant, to: Instant, limit: Int?): List<JsonObject> {
        val records = readRecords(WeightRecord::class, from, to)
        return records
            .map { record ->
                buildJsonObject {
                    put("time", record.time.toString())
                    put("kg", record.weight.inKilograms)
                }
            }
            .sortedBy { it["time"].toString() }
            .let { if (limit != null) it.take(limit) else it }
    }

    private suspend fun queryBloodPressure(
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> {
        val records = readRecords(BloodPressureRecord::class, from, to)
        return records
            .map { record ->
                buildJsonObject {
                    put("time", record.time.toString())
                    put("systolic", record.systolic.inMillimetersOfMercury)
                    put("diastolic", record.diastolic.inMillimetersOfMercury)
                }
            }
            .sortedBy { it["time"].toString() }
            .let { if (limit != null) it.take(limit) else it }
    }

    private suspend fun queryActiveCalories(
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> {
        val records = readRecords(ActiveCaloriesBurnedRecord::class, from, to)
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

    private suspend fun queryDistance(from: Instant, to: Instant, limit: Int?): List<JsonObject> {
        val records = readRecords(DistanceRecord::class, from, to)
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

    private suspend fun queryExercise(from: Instant, to: Instant, limit: Int?): List<JsonObject> {
        val records = readRecords(ExerciseSessionRecord::class, from, to)
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

    private suspend fun queryHydration(from: Instant, to: Instant, limit: Int?): List<JsonObject> {
        val records = readRecords(HydrationRecord::class, from, to)
        return records
            .map { record ->
                buildJsonObject {
                    put("start_time", record.startTime.toString())
                    put("end_time", record.endTime.toString())
                    put("liters", record.volume.inLiters)
                }
            }
            .let { if (limit != null) it.take(limit) else it }
    }

    private suspend fun queryOxygenSaturation(
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> {
        val records = readRecords(OxygenSaturationRecord::class, from, to)
        return records
            .map { record ->
                buildJsonObject {
                    put("time", record.time.toString())
                    put("percentage", record.percentage.value)
                }
            }
            .sortedBy { it["time"].toString() }
            .let { if (limit != null) it.take(limit) else it }
    }

    private suspend fun queryRespiratoryRate(
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> {
        val records = readRecords(RespiratoryRateRecord::class, from, to)
        return records
            .map { record ->
                buildJsonObject {
                    put("time", record.time.toString())
                    put("breaths_per_minute", record.rate)
                }
            }
            .sortedBy { it["time"].toString() }
            .let { if (limit != null) it.take(limit) else it }
    }

    private suspend fun queryNutrition(from: Instant, to: Instant, limit: Int?): List<JsonObject> {
        val records = readRecords(NutritionRecord::class, from, to)
        return records
            .map { record ->
                buildJsonObject {
                    put("start_time", record.startTime.toString())
                    put("end_time", record.endTime.toString())
                    record.name?.let { put("name", it) }
                    record.energy?.let { put("kcal", it.inKilocalories) }
                    record.protein?.let { put("protein_g", it.inGrams) }
                    record.totalFat?.let { put("fat_g", it.inGrams) }
                    record.totalCarbohydrate?.let { put("carbs_g", it.inGrams) }
                }
            }
            .let { if (limit != null) it.take(limit) else it }
    }

    // --- Helpers ---

    private suspend fun <T : Record> readRecords(
        recordType: KClass<T>,
        from: Instant,
        to: Instant
    ): List<T> {
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = recordType,
                timeRangeFilter = TimeRangeFilter.between(from, to)
            )
        )
        return response.records
    }

    private fun mapSleepStage(stage: Int): String = when (stage) {
        SleepSessionRecord.STAGE_TYPE_AWAKE -> "awake"
        SleepSessionRecord.STAGE_TYPE_LIGHT -> "light"
        SleepSessionRecord.STAGE_TYPE_DEEP -> "deep"
        SleepSessionRecord.STAGE_TYPE_REM -> "rem"
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "out_of_bed"
        SleepSessionRecord.STAGE_TYPE_SLEEPING -> "sleeping"
        else -> "unknown"
    }

    companion object {
        private const val MINUTES_PER_HOUR = 60.0

        /** Map record type names to Health Connect SDK record classes. */
        val RECORD_CLASS_MAP: Map<String, KClass<out Record>> = mapOf(
            "Steps" to StepsRecord::class,
            "HeartRate" to HeartRateRecord::class,
            "SleepSession" to SleepSessionRecord::class,
            "Weight" to WeightRecord::class,
            "BloodPressure" to BloodPressureRecord::class,
            "ActiveCaloriesBurned" to ActiveCaloriesBurnedRecord::class,
            "Distance" to DistanceRecord::class,
            "ExerciseSession" to ExerciseSessionRecord::class,
            "Hydration" to HydrationRecord::class,
            "OxygenSaturation" to OxygenSaturationRecord::class,
            "RespiratoryRate" to RespiratoryRateRecord::class,
            "Nutrition" to NutritionRecord::class
        )

        /**
         * All Health Connect read permissions needed for all supported record types.
         */
        val ALL_PERMISSIONS: Set<String> = RECORD_CLASS_MAP.values.map { cls ->
            HealthPermission.getReadPermission(cls)
        }.toSet()
    }
}
