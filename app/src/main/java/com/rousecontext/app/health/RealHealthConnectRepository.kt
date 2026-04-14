package com.rousecontext.app.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalBodyTemperatureRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.CervicalMucusRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.IntermenstrualBleedingRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OvulationTestRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SexualActivityRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.WheelchairPushesRecord
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
@Suppress("LargeClass", "TooManyFunctions")
@OptIn(ExperimentalMindfulnessSessionApi::class)
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
        // Activity
        "Steps" to ::querySteps,
        "ActiveCaloriesBurned" to ::queryActiveCalories,
        "TotalCaloriesBurned" to ::queryTotalCalories,
        "BasalMetabolicRate" to ::queryBasalMetabolicRate,
        "Distance" to ::queryDistance,
        "ElevationGained" to ::queryElevationGained,
        "FloorsClimbed" to ::queryFloorsClimbed,
        "ExerciseSession" to ::queryExercise,
        "Speed" to ::querySpeed,
        "Power" to ::queryPower,
        "CyclingPedalingCadence" to ::queryCyclingPedalingCadence,
        "StepsCadence" to ::queryStepsCadence,
        "WheelchairPushes" to ::queryWheelchairPushes,
        // Body
        "Weight" to ::queryWeight,
        "Height" to ::queryHeight,
        "BodyFat" to ::queryBodyFat,
        "BoneMass" to ::queryBoneMass,
        "LeanBodyMass" to ::queryLeanBodyMass,
        "Vo2Max" to ::queryVo2Max,
        // Sleep
        "SleepSession" to ::querySleep,
        // Vitals
        "HeartRate" to ::queryHeartRate,
        "RestingHeartRate" to ::queryRestingHeartRate,
        "HeartRateVariabilityRmssd" to ::queryHeartRateVariability,
        "BloodPressure" to ::queryBloodPressure,
        "BloodGlucose" to ::queryBloodGlucose,
        "OxygenSaturation" to ::queryOxygenSaturation,
        "RespiratoryRate" to ::queryRespiratoryRate,
        "BodyTemperature" to ::queryBodyTemperature,
        "BasalBodyTemperature" to ::queryBasalBodyTemperature,
        "SkinTemperature" to ::querySkinTemperature,
        // Nutrition
        "Hydration" to ::queryHydration,
        "Nutrition" to ::queryNutrition,
        // Reproductive
        "MenstruationFlow" to ::queryMenstruationFlow,
        "MenstruationPeriod" to ::queryMenstruationPeriod,
        "CervicalMucus" to ::queryCervicalMucus,
        "OvulationTest" to ::queryOvulationTest,
        "IntermenstrualBleeding" to ::queryIntermenstrualBleeding,
        "SexualActivity" to ::querySexualActivity,
        // Mindfulness
        "MindfulnessSession" to ::queryMindfulnessSession
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

    // --- Per-type query methods: Activity ---

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

    private suspend fun queryTotalCalories(
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> {
        val records = readRecords(TotalCaloriesBurnedRecord::class, from, to)
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
        val records = readRecords(BasalMetabolicRateRecord::class, from, to)
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

    private suspend fun queryElevationGained(
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> {
        val records = readRecords(ElevationGainedRecord::class, from, to)
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
        val records = readRecords(FloorsClimbedRecord::class, from, to)
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

    private suspend fun querySpeed(from: Instant, to: Instant, limit: Int?): List<JsonObject> {
        val records = readRecords(SpeedRecord::class, from, to)
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
        val records = readRecords(PowerRecord::class, from, to)
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
        val records = readRecords(CyclingPedalingCadenceRecord::class, from, to)
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
        val records = readRecords(StepsCadenceRecord::class, from, to)
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
        val records = readRecords(WheelchairPushesRecord::class, from, to)
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

    // --- Per-type query methods: Body ---

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

    private suspend fun queryHeight(from: Instant, to: Instant, limit: Int?): List<JsonObject> {
        val records = readRecords(HeightRecord::class, from, to)
        return records
            .map { record ->
                buildJsonObject {
                    put("time", record.time.toString())
                    put("meters", record.height.inMeters)
                }
            }
            .sortedBy { it["time"].toString() }
            .let { if (limit != null) it.take(limit) else it }
    }

    private suspend fun queryBodyFat(from: Instant, to: Instant, limit: Int?): List<JsonObject> {
        val records = readRecords(BodyFatRecord::class, from, to)
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

    private suspend fun queryBoneMass(from: Instant, to: Instant, limit: Int?): List<JsonObject> {
        val records = readRecords(BoneMassRecord::class, from, to)
        return records
            .map { record ->
                buildJsonObject {
                    put("time", record.time.toString())
                    put("kg", record.mass.inKilograms)
                }
            }
            .sortedBy { it["time"].toString() }
            .let { if (limit != null) it.take(limit) else it }
    }

    private suspend fun queryLeanBodyMass(
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> {
        val records = readRecords(LeanBodyMassRecord::class, from, to)
        return records
            .map { record ->
                buildJsonObject {
                    put("time", record.time.toString())
                    put("kg", record.mass.inKilograms)
                }
            }
            .sortedBy { it["time"].toString() }
            .let { if (limit != null) it.take(limit) else it }
    }

    private suspend fun queryVo2Max(from: Instant, to: Instant, limit: Int?): List<JsonObject> {
        val records = readRecords(Vo2MaxRecord::class, from, to)
        return records
            .map { record ->
                buildJsonObject {
                    put("time", record.time.toString())
                    put("ml_per_min_per_kg", record.vo2MillilitersPerMinuteKilogram)
                    put("measurement_method", record.measurementMethod)
                }
            }
            .sortedBy { it["time"].toString() }
            .let { if (limit != null) it.take(limit) else it }
    }

    // --- Per-type query methods: Sleep ---

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

    // --- Per-type query methods: Vitals ---

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

    private suspend fun queryRestingHeartRate(
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> {
        val records = readRecords(RestingHeartRateRecord::class, from, to)
        return records
            .map { record ->
                buildJsonObject {
                    put("time", record.time.toString())
                    put("bpm", record.beatsPerMinute)
                }
            }
            .sortedBy { it["time"].toString() }
            .let { if (limit != null) it.take(limit) else it }
    }

    private suspend fun queryHeartRateVariability(
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> {
        val records = readRecords(HeartRateVariabilityRmssdRecord::class, from, to)
        return records
            .map { record ->
                buildJsonObject {
                    put("time", record.time.toString())
                    put("rmssd_ms", record.heartRateVariabilityMillis)
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

    private suspend fun queryBloodGlucose(
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> {
        val records = readRecords(BloodGlucoseRecord::class, from, to)
        return records
            .map { record ->
                buildJsonObject {
                    put("time", record.time.toString())
                    put("mmol_per_l", record.level.inMillimolesPerLiter)
                }
            }
            .sortedBy { it["time"].toString() }
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

    private suspend fun queryBodyTemperature(
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> {
        val records = readRecords(BodyTemperatureRecord::class, from, to)
        return records
            .map { record ->
                buildJsonObject {
                    put("time", record.time.toString())
                    put("celsius", record.temperature.inCelsius)
                    put("measurement_location", record.measurementLocation)
                }
            }
            .sortedBy { it["time"].toString() }
            .let { if (limit != null) it.take(limit) else it }
    }

    private suspend fun queryBasalBodyTemperature(
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> {
        val records = readRecords(BasalBodyTemperatureRecord::class, from, to)
        return records
            .map { record ->
                buildJsonObject {
                    put("time", record.time.toString())
                    put("celsius", record.temperature.inCelsius)
                    put("measurement_location", record.measurementLocation)
                }
            }
            .sortedBy { it["time"].toString() }
            .let { if (limit != null) it.take(limit) else it }
    }

    private suspend fun querySkinTemperature(
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> {
        val records = readRecords(SkinTemperatureRecord::class, from, to)
        return records
            .map { record ->
                buildJsonObject {
                    put("start_time", record.startTime.toString())
                    put("end_time", record.endTime.toString())
                    record.baseline?.let { put("baseline_celsius", it.inCelsius) }
                    put("measurement_location", record.measurementLocation)
                    put(
                        "deltas",
                        record.deltas.joinToString(",") { delta ->
                            """{"time":"${delta.time}","delta_celsius":${delta.delta.inCelsius}}"""
                        }.let { "[$it]" }
                    )
                }
            }
            .let { if (limit != null) it.take(limit) else it }
    }

    // --- Per-type query methods: Nutrition ---

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

    // --- Per-type query methods: Reproductive ---

    private suspend fun queryMenstruationFlow(
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> {
        val records = readRecords(MenstruationFlowRecord::class, from, to)
        return records
            .map { record ->
                buildJsonObject {
                    put("time", record.time.toString())
                    put("flow", record.flow)
                }
            }
            .sortedBy { it["time"].toString() }
            .let { if (limit != null) it.take(limit) else it }
    }

    private suspend fun queryMenstruationPeriod(
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> {
        val records = readRecords(MenstruationPeriodRecord::class, from, to)
        return records
            .map { record ->
                buildJsonObject {
                    put("start_time", record.startTime.toString())
                    put("end_time", record.endTime.toString())
                }
            }
            .let { if (limit != null) it.take(limit) else it }
    }

    private suspend fun queryCervicalMucus(
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> {
        val records = readRecords(CervicalMucusRecord::class, from, to)
        return records
            .map { record ->
                buildJsonObject {
                    put("time", record.time.toString())
                    put("appearance", record.appearance)
                    put("sensation", record.sensation)
                }
            }
            .sortedBy { it["time"].toString() }
            .let { if (limit != null) it.take(limit) else it }
    }

    private suspend fun queryOvulationTest(
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> {
        val records = readRecords(OvulationTestRecord::class, from, to)
        return records
            .map { record ->
                buildJsonObject {
                    put("time", record.time.toString())
                    put("result", record.result)
                }
            }
            .sortedBy { it["time"].toString() }
            .let { if (limit != null) it.take(limit) else it }
    }

    private suspend fun queryIntermenstrualBleeding(
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> {
        val records = readRecords(IntermenstrualBleedingRecord::class, from, to)
        return records
            .map { record ->
                buildJsonObject {
                    put("time", record.time.toString())
                }
            }
            .sortedBy { it["time"].toString() }
            .let { if (limit != null) it.take(limit) else it }
    }

    private suspend fun querySexualActivity(
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> {
        val records = readRecords(SexualActivityRecord::class, from, to)
        return records
            .map { record ->
                buildJsonObject {
                    put("time", record.time.toString())
                    put("protection_used", record.protectionUsed)
                }
            }
            .sortedBy { it["time"].toString() }
            .let { if (limit != null) it.take(limit) else it }
    }

    // --- Per-type query methods: Mindfulness ---

    private suspend fun queryMindfulnessSession(
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> {
        val records = readRecords(MindfulnessSessionRecord::class, from, to)
        return records
            .map { record ->
                buildJsonObject {
                    put("start_time", record.startTime.toString())
                    put("end_time", record.endTime.toString())
                    put("session_type", record.mindfulnessSessionType)
                    record.title?.let { put("title", it) }
                    record.notes?.let { put("notes", it) }
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
            // Activity
            "Steps" to StepsRecord::class,
            "ActiveCaloriesBurned" to ActiveCaloriesBurnedRecord::class,
            "TotalCaloriesBurned" to TotalCaloriesBurnedRecord::class,
            "BasalMetabolicRate" to BasalMetabolicRateRecord::class,
            "Distance" to DistanceRecord::class,
            "ElevationGained" to ElevationGainedRecord::class,
            "FloorsClimbed" to FloorsClimbedRecord::class,
            "ExerciseSession" to ExerciseSessionRecord::class,
            "Speed" to SpeedRecord::class,
            "Power" to PowerRecord::class,
            "CyclingPedalingCadence" to CyclingPedalingCadenceRecord::class,
            "StepsCadence" to StepsCadenceRecord::class,
            "WheelchairPushes" to WheelchairPushesRecord::class,
            // Body
            "Weight" to WeightRecord::class,
            "Height" to HeightRecord::class,
            "BodyFat" to BodyFatRecord::class,
            "BoneMass" to BoneMassRecord::class,
            "LeanBodyMass" to LeanBodyMassRecord::class,
            "Vo2Max" to Vo2MaxRecord::class,
            // Sleep
            "SleepSession" to SleepSessionRecord::class,
            // Vitals
            "HeartRate" to HeartRateRecord::class,
            "RestingHeartRate" to RestingHeartRateRecord::class,
            "HeartRateVariabilityRmssd" to HeartRateVariabilityRmssdRecord::class,
            "BloodPressure" to BloodPressureRecord::class,
            "BloodGlucose" to BloodGlucoseRecord::class,
            "OxygenSaturation" to OxygenSaturationRecord::class,
            "RespiratoryRate" to RespiratoryRateRecord::class,
            "BodyTemperature" to BodyTemperatureRecord::class,
            "BasalBodyTemperature" to BasalBodyTemperatureRecord::class,
            "SkinTemperature" to SkinTemperatureRecord::class,
            // Nutrition
            "Hydration" to HydrationRecord::class,
            "Nutrition" to NutritionRecord::class,
            // Reproductive
            "MenstruationFlow" to MenstruationFlowRecord::class,
            "MenstruationPeriod" to MenstruationPeriodRecord::class,
            "CervicalMucus" to CervicalMucusRecord::class,
            "OvulationTest" to OvulationTestRecord::class,
            "IntermenstrualBleeding" to IntermenstrualBleedingRecord::class,
            "SexualActivity" to SexualActivityRecord::class,
            // Mindfulness
            "MindfulnessSession" to MindfulnessSessionRecord::class
        )

        /**
         * All Health Connect read permissions needed for all supported record types.
         */
        val ALL_PERMISSIONS: Set<String> = RECORD_CLASS_MAP.values.map { cls ->
            HealthPermission.getReadPermission(cls)
        }.toSet()
    }
}
