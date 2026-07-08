package com.rousecontext.integrations.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.time.TimeRangeFilter
import com.rousecontext.integrations.health.query.ActivityQueries
import com.rousecontext.integrations.health.query.BodyQueries
import com.rousecontext.integrations.health.query.CategoryQueries
import com.rousecontext.integrations.health.query.MindfulnessQueries
import com.rousecontext.integrations.health.query.NutritionQueries
import com.rousecontext.integrations.health.query.RecordReader
import com.rousecontext.integrations.health.query.ReproductiveQueries
import com.rousecontext.integrations.health.query.SleepQueries
import com.rousecontext.integrations.health.query.VitalsQueries
import com.rousecontext.integrations.health.query.bucketize
import com.rousecontext.integrations.health.query.downsampleEvenly
import java.time.Duration
import java.time.Instant
import kotlin.reflect.KClass
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Production [HealthConnectRepository] backed by the Health Connect SDK.
 *
 * Obtains a [HealthConnectClient] via the supplied Android [Context]. Per-type
 * query logic lives in category classes under
 * [com.rousecontext.integrations.health.query]; this class is a thin dispatcher that
 * looks up the category via [RecordTypeRegistry] and delegates.
 */
class RealHealthConnectRepository internal constructor(
    private val categoriesProvider: () -> List<CategoryQueries>,
    private val grantedPermissionsProvider: suspend () -> Set<String>,
    private val historicalReadGrantedProvider: suspend () -> Boolean
) : HealthConnectRepository {

    /** Production constructor; lazily builds the HC client from [context]. */
    constructor(context: Context) : this(
        clientProvider = lazyHealthConnectClient(context)
    )

    private constructor(clientProvider: Lazy<HealthConnectClient>) : this(
        categoriesProvider = {
            val reader = HealthConnectClientRecordReader(clientProvider.value)
            listOf(
                ActivityQueries(reader),
                BodyQueries(reader),
                SleepQueries(reader),
                VitalsQueries(reader),
                NutritionQueries(reader),
                ReproductiveQueries(reader),
                MindfulnessQueries(reader)
            )
        },
        grantedPermissionsProvider = {
            val granted = clientProvider.value.permissionController.getGrantedPermissions()
            RecordTypeRegistry.allTypes
                .filter { info -> granted.contains(info.readPermission) }
                .map { it.name }
                .toSet()
        },
        historicalReadGrantedProvider = {
            HEALTH_DATA_HISTORY_PERMISSION in
                clientProvider.value.permissionController.getGrantedPermissions()
        }
    )

    private val categories: List<CategoryQueries> by lazy(categoriesProvider)

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
    ): QueryResult {
        val category = categoryFor(recordType)
        // Read raw (paginated inside the reader); apply the cap as an even
        // downsample so coverage spans the whole range rather than trimming one end.
        val mapped = category.query(recordType, from, to, limit = null)
        val cap = limit ?: DEFAULT_MAX_RECORDS
        return if (mapped.size > cap) {
            QueryResult(
                records = downsampleEvenly(mapped, cap),
                totalCount = mapped.size,
                downsampled = true
            )
        } else {
            QueryResult(records = mapped, totalCount = mapped.size, downsampled = false)
        }
    }

    override suspend fun bucketRecords(
        recordType: String,
        from: Instant,
        to: Instant,
        bucket: Duration
    ): BucketResult {
        val category = categoryFor(recordType)
        // Guard BEFORE reading: reject a too-fine bucket over a wide range.
        val spanMillis = (to.toEpochMilli() - from.toEpochMilli()).coerceAtLeast(0)
        val widthMillis = bucket.toMillis()
        val expected = if (widthMillis <= 0) {
            Long.MAX_VALUE
        } else {
            (spanMillis + widthMillis - 1) / widthMillis
        }
        if (expected > MAX_BUCKETS) {
            return BucketResult.Error(
                "Bucket too fine: $expected buckets exceeds the max of $MAX_BUCKETS. " +
                    "Use a coarser bucket or a narrower time range."
            )
        }
        val values = category.bucketValues(recordType, from, to)
            ?: return BucketResult.Error(
                "Bucketing not supported for $recordType " +
                    "(session/multi-value/cumulative)."
            )
        return BucketResult.Success(bucketize(values, from, bucket))
    }

    private fun categoryFor(recordType: String): CategoryQueries {
        RecordTypeRegistry[recordType]
            ?: throw IllegalArgumentException("Unknown record type: $recordType")
        return categoryByRecordType[recordType]
            ?: throw IllegalArgumentException("No query handler for: $recordType")
    }

    override suspend fun getGrantedPermissions(): Set<String> = grantedPermissionsProvider()

    override suspend fun isHistoricalReadGranted(): Boolean = historicalReadGrantedProvider()

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

    companion object {
        private fun lazyHealthConnectClient(context: Context): Lazy<HealthConnectClient> = lazy {
            val status = HealthConnectClient.getSdkStatus(context)
            if (status != HealthConnectClient.SDK_AVAILABLE) {
                throw HealthConnectUnavailableException()
            }
            HealthConnectClient.getOrCreate(context)
        }
    }
}

/** Fetches a single page of records; seam for testing the pagination loop. */
internal typealias PageFetcher =
    suspend (request: ReadRecordsRequest<out Record>) -> ReadRecordsResponse<out Record>

/**
 * [RecordReader] implementation backed by a [HealthConnectClient].
 *
 * Reads are paginated with a bounded [READ_PAGE_SIZE] and looped over the
 * response `pageToken` until exhausted, so a single Binder transaction never
 * carries a full unbounded page — the crash for high-volume types (e.g.
 * BloodGlucose from a CGM). Accumulation stops at [MAX_RECORDS] to bound memory.
 *
 * [fetchPage] is a seam so pagination can be unit-tested without a real client.
 */
internal class HealthConnectClientRecordReader(private val fetchPage: PageFetcher) : RecordReader {

    constructor(client: HealthConnectClient) : this(
        fetchPage = { request -> client.readRecords(request) }
    )

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Record> read(type: KClass<T>, from: Instant, to: Instant): List<T> {
        val filter = TimeRangeFilter.between(from, to)
        val accumulated = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response = fetchPage(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = filter,
                    pageSize = READ_PAGE_SIZE,
                    pageToken = pageToken
                )
            ) as ReadRecordsResponse<T>
            accumulated += response.records
            pageToken = response.pageToken
        } while (pageToken != null && accumulated.size < MAX_RECORDS)
        return accumulated
    }
}
