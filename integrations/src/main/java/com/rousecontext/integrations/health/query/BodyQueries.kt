package com.rousecontext.integrations.health.query

import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Body-category record queries: weight, height, body fat, etc.
 */
class BodyQueries(private val reader: RecordReader) : CategoryQueries {

    override val recordTypes: Set<String> = setOf(
        "Weight",
        "Height",
        "BodyFat",
        "BoneMass",
        "LeanBodyMass",
        "Vo2Max"
    )

    @Suppress("LongMethod")
    override suspend fun query(
        recordType: String,
        from: Instant,
        to: Instant,
        maxRecords: Int
    ): List<JsonObject> = when (recordType) {
        "Weight" -> reader.queryRecords(
            WeightRecord::class,
            from,
            to,
            maxRecords,
            sortByTime = true
        ) { record ->
            listOf(
                buildJsonObject {
                    put("time", record.time.toString())
                    put("kg", record.weight.inKilograms)
                }
            )
        }
        "Height" -> reader.queryRecords(
            HeightRecord::class,
            from,
            to,
            maxRecords,
            sortByTime = true
        ) { record ->
            listOf(
                buildJsonObject {
                    put("time", record.time.toString())
                    put("meters", record.height.inMeters)
                }
            )
        }
        "BodyFat" -> reader.queryRecords(
            BodyFatRecord::class,
            from,
            to,
            maxRecords,
            sortByTime = true
        ) { record ->
            listOf(
                buildJsonObject {
                    put("time", record.time.toString())
                    put("percentage", record.percentage.value)
                }
            )
        }
        "BoneMass" -> reader.queryRecords(
            BoneMassRecord::class,
            from,
            to,
            maxRecords,
            sortByTime = true
        ) { record ->
            listOf(
                buildJsonObject {
                    put("time", record.time.toString())
                    put("kg", record.mass.inKilograms)
                }
            )
        }
        "LeanBodyMass" -> reader.queryRecords(
            LeanBodyMassRecord::class,
            from,
            to,
            maxRecords,
            sortByTime = true
        ) { record ->
            listOf(
                buildJsonObject {
                    put("time", record.time.toString())
                    put("kg", record.mass.inKilograms)
                }
            )
        }
        "Vo2Max" -> reader.queryRecords(
            Vo2MaxRecord::class,
            from,
            to,
            maxRecords,
            sortByTime = true
        ) { record ->
            listOf(
                buildJsonObject {
                    put("time", record.time.toString())
                    put("ml_per_min_per_kg", record.vo2MillilitersPerMinuteKilogram)
                    put("measurement_method", record.measurementMethod)
                }
            )
        }
        else -> throw IllegalArgumentException("Unsupported record type: $recordType")
    }

    override fun bucketValues(
        recordType: String,
        from: Instant,
        to: Instant
    ): Flow<Streamed<TimedValue>>? = when (recordType) {
        "Weight" -> reader.stream(WeightRecord::class, from, to)
            .mapValues { TimedValue(it.time, it.weight.inKilograms) }
        "Height" -> reader.stream(HeightRecord::class, from, to)
            .mapValues { TimedValue(it.time, it.height.inMeters) }
        "BodyFat" -> reader.stream(BodyFatRecord::class, from, to)
            .mapValues { TimedValue(it.time, it.percentage.value) }
        "BoneMass" -> reader.stream(BoneMassRecord::class, from, to)
            .mapValues { TimedValue(it.time, it.mass.inKilograms) }
        "LeanBodyMass" -> reader.stream(LeanBodyMassRecord::class, from, to)
            .mapValues { TimedValue(it.time, it.mass.inKilograms) }
        "Vo2Max" -> reader.stream(Vo2MaxRecord::class, from, to)
            .mapValues { TimedValue(it.time, it.vo2MillilitersPerMinuteKilogram) }
        else -> null
    }

    override suspend fun summary(from: Instant, to: Instant, granted: Set<String>): JsonObject =
        buildJsonObject {
            if ("Weight" in granted) {
                val weights = query("Weight", from, to)
                val latest = weights.lastOrNull()
                if (latest != null) {
                    val kg = latest["kg"]?.toString()?.toDoubleOrNull()
                    if (kg != null) put("weight_latest_kg", kg)
                }
            }
        }
}
