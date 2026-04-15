package com.rousecontext.integrations.health.query

import androidx.health.connect.client.records.CervicalMucusRecord
import androidx.health.connect.client.records.IntermenstrualBleedingRecord
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.OvulationTestRecord
import androidx.health.connect.client.records.SexualActivityRecord
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Reproductive-category record queries: menstrual cycle, ovulation, sexual activity.
 */
class ReproductiveQueries(private val reader: RecordReader) : CategoryQueries {

    override val recordTypes: Set<String> = setOf(
        "MenstruationFlow",
        "MenstruationPeriod",
        "CervicalMucus",
        "OvulationTest",
        "IntermenstrualBleeding",
        "SexualActivity"
    )

    override suspend fun query(
        recordType: String,
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> = when (recordType) {
        "MenstruationFlow" -> queryMenstruationFlow(from, to, limit)
        "MenstruationPeriod" -> queryMenstruationPeriod(from, to, limit)
        "CervicalMucus" -> queryCervicalMucus(from, to, limit)
        "OvulationTest" -> queryOvulationTest(from, to, limit)
        "IntermenstrualBleeding" -> queryIntermenstrualBleeding(from, to, limit)
        "SexualActivity" -> querySexualActivity(from, to, limit)
        else -> throw IllegalArgumentException("Unsupported record type: $recordType")
    }

    override suspend fun summary(from: Instant, to: Instant, granted: Set<String>): JsonObject =
        JsonObject(emptyMap())

    private suspend fun queryMenstruationFlow(
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> {
        val records = reader.read(MenstruationFlowRecord::class, from, to)
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
        val records = reader.read(MenstruationPeriodRecord::class, from, to)
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
        val records = reader.read(CervicalMucusRecord::class, from, to)
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
        val records = reader.read(OvulationTestRecord::class, from, to)
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
        val records = reader.read(IntermenstrualBleedingRecord::class, from, to)
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
        val records = reader.read(SexualActivityRecord::class, from, to)
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
}
