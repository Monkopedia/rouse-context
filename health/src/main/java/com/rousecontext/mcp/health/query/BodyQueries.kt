package com.rousecontext.mcp.health.query

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

    override suspend fun query(
        recordType: String,
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> = when (recordType) {
        "Weight" -> queryWeight(from, to, limit)
        "Height" -> queryHeight(from, to, limit)
        "BodyFat" -> queryBodyFat(from, to, limit)
        "BoneMass" -> queryBoneMass(from, to, limit)
        "LeanBodyMass" -> queryLeanBodyMass(from, to, limit)
        "Vo2Max" -> queryVo2Max(from, to, limit)
        else -> throw IllegalArgumentException("Unsupported record type: $recordType")
    }

    override suspend fun summary(from: Instant, to: Instant, granted: Set<String>): JsonObject =
        buildJsonObject {
            if ("Weight" in granted) {
                val weights = queryWeight(from, to, null)
                val latest = weights.lastOrNull()
                if (latest != null) {
                    val kg = latest["kg"]?.toString()?.toDoubleOrNull()
                    if (kg != null) put("weight_latest_kg", kg)
                }
            }
        }

    private suspend fun queryWeight(from: Instant, to: Instant, limit: Int?): List<JsonObject> {
        val records = reader.read(WeightRecord::class, from, to)
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
        val records = reader.read(HeightRecord::class, from, to)
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
        val records = reader.read(BodyFatRecord::class, from, to)
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
        val records = reader.read(BoneMassRecord::class, from, to)
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
        val records = reader.read(LeanBodyMassRecord::class, from, to)
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
        val records = reader.read(Vo2MaxRecord::class, from, to)
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
}
