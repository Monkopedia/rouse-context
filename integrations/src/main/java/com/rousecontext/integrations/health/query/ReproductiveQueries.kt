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

    @Suppress("LongMethod")
    override suspend fun query(
        recordType: String,
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> = when (recordType) {
        "MenstruationFlow" -> reader.queryRecords(
            MenstruationFlowRecord::class,
            from,
            to,
            limit,
            sortByTime = true
        ) { record ->
            listOf(
                buildJsonObject {
                    put("time", record.time.toString())
                    put("flow", record.flow)
                }
            )
        }
        "MenstruationPeriod" -> reader.queryRecords(
            MenstruationPeriodRecord::class,
            from,
            to,
            limit
        ) { record ->
            listOf(
                buildJsonObject {
                    put("start_time", record.startTime.toString())
                    put("end_time", record.endTime.toString())
                }
            )
        }
        "CervicalMucus" -> reader.queryRecords(
            CervicalMucusRecord::class,
            from,
            to,
            limit,
            sortByTime = true
        ) { record ->
            listOf(
                buildJsonObject {
                    put("time", record.time.toString())
                    put("appearance", record.appearance)
                    put("sensation", record.sensation)
                }
            )
        }
        "OvulationTest" -> reader.queryRecords(
            OvulationTestRecord::class,
            from,
            to,
            limit,
            sortByTime = true
        ) { record ->
            listOf(
                buildJsonObject {
                    put("time", record.time.toString())
                    put("result", record.result)
                }
            )
        }
        "IntermenstrualBleeding" -> reader.queryRecords(
            IntermenstrualBleedingRecord::class,
            from,
            to,
            limit,
            sortByTime = true
        ) { record ->
            listOf(
                buildJsonObject {
                    put("time", record.time.toString())
                }
            )
        }
        "SexualActivity" -> reader.queryRecords(
            SexualActivityRecord::class,
            from,
            to,
            limit,
            sortByTime = true
        ) { record ->
            listOf(
                buildJsonObject {
                    put("time", record.time.toString())
                    put("protection_used", record.protectionUsed)
                }
            )
        }
        else -> throw IllegalArgumentException("Unsupported record type: $recordType")
    }

    override suspend fun summary(from: Instant, to: Instant, granted: Set<String>): JsonObject =
        JsonObject(emptyMap())
}
