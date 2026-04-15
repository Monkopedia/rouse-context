package com.rousecontext.integrations.health.query

import androidx.health.connect.client.records.BasalBodyTemperatureRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Vitals-category record queries: heart rate, blood pressure, temperature, etc.
 */
class VitalsQueries(private val reader: RecordReader) : CategoryQueries {

    override val recordTypes: Set<String> = setOf(
        "HeartRate",
        "RestingHeartRate",
        "HeartRateVariabilityRmssd",
        "BloodPressure",
        "BloodGlucose",
        "OxygenSaturation",
        "RespiratoryRate",
        "BodyTemperature",
        "BasalBodyTemperature",
        "SkinTemperature"
    )

    @Suppress("CyclomaticComplexMethod")
    override suspend fun query(
        recordType: String,
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> = when (recordType) {
        "HeartRate" -> queryHeartRate(from, to, limit)
        "RestingHeartRate" -> queryRestingHeartRate(from, to, limit)
        "HeartRateVariabilityRmssd" -> queryHeartRateVariability(from, to, limit)
        "BloodPressure" -> queryBloodPressure(from, to, limit)
        "BloodGlucose" -> queryBloodGlucose(from, to, limit)
        "OxygenSaturation" -> queryOxygenSaturation(from, to, limit)
        "RespiratoryRate" -> queryRespiratoryRate(from, to, limit)
        "BodyTemperature" -> queryBodyTemperature(from, to, limit)
        "BasalBodyTemperature" -> queryBasalBodyTemperature(from, to, limit)
        "SkinTemperature" -> querySkinTemperature(from, to, limit)
        else -> throw IllegalArgumentException("Unsupported record type: $recordType")
    }

    override suspend fun summary(from: Instant, to: Instant, granted: Set<String>): JsonObject =
        buildJsonObject {
            if ("HeartRate" in granted) {
                val samples = queryHeartRate(from, to, null)
                if (samples.isNotEmpty()) {
                    val avg = samples.mapNotNull {
                        it["bpm"]?.toString()?.toLongOrNull()
                    }.average()
                    put("avg_heart_rate", avg)
                }
            }
        }

    private suspend fun queryHeartRate(from: Instant, to: Instant, limit: Int?): List<JsonObject> {
        val records = reader.read(HeartRateRecord::class, from, to)
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
        val records = reader.read(RestingHeartRateRecord::class, from, to)
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
        val records = reader.read(HeartRateVariabilityRmssdRecord::class, from, to)
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
        val records = reader.read(BloodPressureRecord::class, from, to)
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
        val records = reader.read(BloodGlucoseRecord::class, from, to)
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
        val records = reader.read(OxygenSaturationRecord::class, from, to)
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
        val records = reader.read(RespiratoryRateRecord::class, from, to)
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
        val records = reader.read(BodyTemperatureRecord::class, from, to)
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
        val records = reader.read(BasalBodyTemperatureRecord::class, from, to)
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
        val records = reader.read(SkinTemperatureRecord::class, from, to)
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
}
