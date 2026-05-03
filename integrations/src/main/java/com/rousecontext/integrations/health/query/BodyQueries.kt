package com.rousecontext.integrations.health.query

import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import java.time.Instant
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
        limit: Int?
    ): List<JsonObject> = when (recordType) {
        "Weight" -> reader.queryRecords(
            WeightRecord::class,
            from,
            to,
            limit,
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
            limit,
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
        "BoneMass" -> reader.queryRecords(
            BoneMassRecord::class,
            from,
            to,
            limit,
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
            limit,
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
            limit,
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

    override suspend fun summary(from: Instant, to: Instant, granted: Set<String>): JsonObject =
        buildJsonObject {
            if ("Weight" in granted) {
                val weights = query("Weight", from, to, null)
                val latest = weights.lastOrNull()
                if (latest != null) {
                    val kg = latest["kg"]?.toString()?.toDoubleOrNull()
                    if (kg != null) put("weight_latest_kg", kg)
                }
            }
        }
}
