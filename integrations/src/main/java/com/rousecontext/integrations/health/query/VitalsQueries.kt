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
import kotlinx.serialization.json.buildJsonArray
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

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    override suspend fun query(
        recordType: String,
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> = when (recordType) {
        "HeartRate" -> reader.queryRecords(
            HeartRateRecord::class,
            from,
            to,
            limit,
            sortByTime = true
        ) { record ->
            record.samples.map { sample ->
                buildJsonObject {
                    put("time", sample.time.toString())
                    put("bpm", sample.beatsPerMinute)
                }
            }
        }
        "RestingHeartRate" -> reader.queryRecords(
            RestingHeartRateRecord::class,
            from,
            to,
            limit,
            sortByTime = true
        ) { record ->
            listOf(
                buildJsonObject {
                    put("time", record.time.toString())
                    put("bpm", record.beatsPerMinute)
                }
            )
        }
        "HeartRateVariabilityRmssd" -> reader.queryRecords(
            HeartRateVariabilityRmssdRecord::class,
            from,
            to,
            limit,
            sortByTime = true
        ) { record ->
            listOf(
                buildJsonObject {
                    put("time", record.time.toString())
                    put("rmssd_ms", record.heartRateVariabilityMillis)
                }
            )
        }
        "BloodPressure" -> reader.queryRecords(
            BloodPressureRecord::class,
            from,
            to,
            limit,
            sortByTime = true
        ) { record ->
            listOf(
                buildJsonObject {
                    put("time", record.time.toString())
                    put("systolic", record.systolic.inMillimetersOfMercury)
                    put("diastolic", record.diastolic.inMillimetersOfMercury)
                }
            )
        }
        "BloodGlucose" -> reader.queryRecords(
            BloodGlucoseRecord::class,
            from,
            to,
            limit,
            sortByTime = true
        ) { record ->
            listOf(
                buildJsonObject {
                    put("time", record.time.toString())
                    put("mmol_per_l", record.level.inMillimolesPerLiter)
                }
            )
        }
        "OxygenSaturation" -> reader.queryRecords(
            OxygenSaturationRecord::class,
            from,
            to,
            limit,
            sortByTime = true
        ) { record ->
            listOf(
                buildJsonObject {
                    put("time", record.time.toString())
                    put("percentage", record.percentage.value)
                }
            )
        }
        "RespiratoryRate" -> reader.queryRecords(
            RespiratoryRateRecord::class,
            from,
            to,
            limit,
            sortByTime = true
        ) { record ->
            listOf(
                buildJsonObject {
                    put("time", record.time.toString())
                    put("breaths_per_minute", record.rate)
                }
            )
        }
        "BodyTemperature" -> reader.queryRecords(
            BodyTemperatureRecord::class,
            from,
            to,
            limit,
            sortByTime = true
        ) { record ->
            listOf(
                buildJsonObject {
                    put("time", record.time.toString())
                    put("celsius", record.temperature.inCelsius)
                    put("measurement_location", record.measurementLocation)
                }
            )
        }
        "BasalBodyTemperature" -> reader.queryRecords(
            BasalBodyTemperatureRecord::class,
            from,
            to,
            limit,
            sortByTime = true
        ) { record ->
            listOf(
                buildJsonObject {
                    put("time", record.time.toString())
                    put("celsius", record.temperature.inCelsius)
                    put("measurement_location", record.measurementLocation)
                }
            )
        }
        "SkinTemperature" -> reader.queryRecords(
            SkinTemperatureRecord::class,
            from,
            to,
            limit
        ) { record ->
            val deltasArray = buildJsonArray {
                record.deltas.forEach { delta ->
                    add(
                        buildJsonObject {
                            put("time", delta.time.toString())
                            put("delta_celsius", delta.delta.inCelsius)
                        }
                    )
                }
            }
            listOf(
                buildJsonObject {
                    put("start_time", record.startTime.toString())
                    put("end_time", record.endTime.toString())
                    record.baseline?.let { put("baseline_celsius", it.inCelsius) }
                    put("measurement_location", record.measurementLocation)
                    put("deltas", deltasArray.toString())
                }
            )
        }
        else -> throw IllegalArgumentException("Unsupported record type: $recordType")
    }

    override suspend fun summary(from: Instant, to: Instant, granted: Set<String>): JsonObject =
        buildJsonObject {
            if ("HeartRate" in granted) {
                val samples = query("HeartRate", from, to, null)
                if (samples.isNotEmpty()) {
                    val avg = samples.mapNotNull {
                        it["bpm"]?.toString()?.toLongOrNull()
                    }.average()
                    put("avg_heart_rate", avg)
                }
            }
        }
}
