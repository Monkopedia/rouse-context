package com.rousecontext.mcp.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.rousecontext.mcp.health.query.ActivityQueries
import com.rousecontext.mcp.health.query.BodyQueries
import com.rousecontext.mcp.health.query.CategoryQueries
import com.rousecontext.mcp.health.query.MindfulnessQueries
import com.rousecontext.mcp.health.query.NutritionQueries
import com.rousecontext.mcp.health.query.RecordReader
import com.rousecontext.mcp.health.query.ReproductiveQueries
import com.rousecontext.mcp.health.query.SleepQueries
import com.rousecontext.mcp.health.query.VitalsQueries
import java.time.Instant
import kotlin.reflect.KClass
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Production [HealthConnectRepository] backed by the Health Connect SDK.
 *
 * Obtains a [HealthConnectClient] via the supplied Android [Context]. Per-type
 * query logic lives in category classes under
 * [com.rousecontext.mcp.health.query]; this class is a thin dispatcher that
 * looks up the category via [RecordTypeRegistry] and delegates.
 */
class RealHealthConnectRepository internal constructor(
    private val context: Context,
    readerFactory: (HealthConnectClient) -> RecordReader,
    categoriesFactory: (RecordReader) -> List<CategoryQueries>
) : HealthConnectRepository {

    constructor(context: Context) : this(
        context = context,
        readerFactory = { client -> HealthConnectClientRecordReader(client) },
        categoriesFactory = { reader ->
            listOf(
                ActivityQueries(reader),
                BodyQueries(reader),
                SleepQueries(reader),
                VitalsQueries(reader),
                NutritionQueries(reader),
                ReproductiveQueries(reader),
                MindfulnessQueries(reader)
            )
        }
    )

    private val client: HealthConnectClient by lazy {
        val status = HealthConnectClient.getSdkStatus(context)
        if (status != HealthConnectClient.SDK_AVAILABLE) {
            throw HealthConnectUnavailableException()
        }
        HealthConnectClient.getOrCreate(context)
    }

    private val categories: List<CategoryQueries> by lazy {
        categoriesFactory(readerFactory(client))
    }

    private val categoryByRecordType: Map<String, CategoryQueries> by lazy {
        buildMap {
            for (category in categories) {
                for (recordType in category.recordTypes) {
                    put(recordType, category)
                }
            }
        }
    }

    override suspend fun queryRecords(
        recordType: String,
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> {
        RecordTypeRegistry[recordType]
            ?: throw IllegalArgumentException("Unknown record type: $recordType")
        val category = categoryByRecordType[recordType]
            ?: throw IllegalArgumentException("No query handler for: $recordType")
        return category.query(recordType, from, to, limit)
    }

    override suspend fun getGrantedPermissions(): Set<String> {
        val granted = client.permissionController.getGrantedPermissions()
        return RecordTypeRegistry.allTypes
            .filter { info -> granted.contains(info.readPermission) }
            .map { it.name }
            .toSet()
    }

    override suspend fun isHistoricalReadGranted(): Boolean {
        val granted = client.permissionController.getGrantedPermissions()
        return HEALTH_DATA_HISTORY_PERMISSION in granted
    }

    override suspend fun getSummary(from: Instant, to: Instant): JsonObject {
        val granted = getGrantedPermissions()
        return buildJsonObject {
            for (category in categories) {
                val contribution = category.summary(from, to, granted)
                for ((key, value) in contribution) {
                    put(key, value)
                }
            }
        }
    }
}

/**
 * [RecordReader] implementation backed by a [HealthConnectClient].
 */
private class HealthConnectClientRecordReader(
    private val client: HealthConnectClient
) : RecordReader {
    override suspend fun <T : Record> read(
        type: KClass<T>,
        from: Instant,
        to: Instant
    ): List<T> {
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = type,
                timeRangeFilter = TimeRangeFilter.between(from, to)
            )
        )
        return response.records
    }
}
