package com.rousecontext.mcp.health.query

import androidx.health.connect.client.records.SleepSessionRecord
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Sleep-category record queries: sleep sessions with stage breakdowns.
 */
class SleepQueries(private val reader: RecordReader) : CategoryQueries {

    override val recordTypes: Set<String> = setOf("SleepSession")

    override suspend fun query(
        recordType: String,
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> = when (recordType) {
        "SleepSession" -> querySleep(from, to, limit)
        else -> throw IllegalArgumentException("Unsupported record type: $recordType")
    }

    override suspend fun summary(
        from: Instant,
        to: Instant,
        granted: Set<String>
    ): JsonObject = buildJsonObject {
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
    }

    private suspend fun querySleep(from: Instant, to: Instant, limit: Int?): List<JsonObject> {
        val records = reader.read(SleepSessionRecord::class, from, to)
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
    }
}
