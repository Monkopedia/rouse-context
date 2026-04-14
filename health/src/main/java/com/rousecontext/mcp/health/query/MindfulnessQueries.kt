package com.rousecontext.mcp.health.query

import androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi
import androidx.health.connect.client.records.MindfulnessSessionRecord
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Mindfulness-category record queries: meditation, breathing sessions.
 */
@OptIn(ExperimentalMindfulnessSessionApi::class)
class MindfulnessQueries(private val reader: RecordReader) : CategoryQueries {

    override val recordTypes: Set<String> = setOf("MindfulnessSession")

    override suspend fun query(
        recordType: String,
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> = when (recordType) {
        "MindfulnessSession" -> queryMindfulnessSession(from, to, limit)
        else -> throw IllegalArgumentException("Unsupported record type: $recordType")
    }

    override suspend fun summary(from: Instant, to: Instant, granted: Set<String>): JsonObject =
        JsonObject(emptyMap())

    private suspend fun queryMindfulnessSession(
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> {
        val records = reader.read(MindfulnessSessionRecord::class, from, to)
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
}
