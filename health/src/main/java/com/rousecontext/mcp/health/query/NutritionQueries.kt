package com.rousecontext.mcp.health.query

import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Nutrition-category record queries: hydration, nutrition.
 */
class NutritionQueries(private val reader: RecordReader) : CategoryQueries {

    override val recordTypes: Set<String> = setOf("Hydration", "Nutrition")

    override suspend fun query(
        recordType: String,
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> = when (recordType) {
        "Hydration" -> queryHydration(from, to, limit)
        "Nutrition" -> queryNutrition(from, to, limit)
        else -> throw IllegalArgumentException("Unsupported record type: $recordType")
    }

    override suspend fun summary(from: Instant, to: Instant, granted: Set<String>): JsonObject =
        JsonObject(emptyMap())

    private suspend fun queryHydration(from: Instant, to: Instant, limit: Int?): List<JsonObject> {
        val records = reader.read(HydrationRecord::class, from, to)
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
        val records = reader.read(NutritionRecord::class, from, to)
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
}
