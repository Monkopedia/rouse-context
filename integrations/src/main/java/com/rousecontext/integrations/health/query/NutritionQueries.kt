package com.rousecontext.integrations.health.query

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
        "Hydration" -> reader.queryRecords(
            HydrationRecord::class,
            from,
            to,
            limit
        ) { record ->
            listOf(
                buildJsonObject {
                    put("start_time", record.startTime.toString())
                    put("end_time", record.endTime.toString())
                    put("liters", record.volume.inLiters)
                }
            )
        }
        "Nutrition" -> reader.queryRecords(
            NutritionRecord::class,
            from,
            to,
            limit
        ) { record ->
            listOf(
                buildJsonObject {
                    put("start_time", record.startTime.toString())
                    put("end_time", record.endTime.toString())
                    record.name?.let { put("name", it) }
                    record.energy?.let { put("kcal", it.inKilocalories) }
                    record.protein?.let { put("protein_g", it.inGrams) }
                    record.totalFat?.let { put("fat_g", it.inGrams) }
                    record.totalCarbohydrate?.let { put("carbs_g", it.inGrams) }
                }
            )
        }
        else -> throw IllegalArgumentException("Unsupported record type: $recordType")
    }

    override suspend fun summary(from: Instant, to: Instant, granted: Set<String>): JsonObject =
        JsonObject(emptyMap())
}
