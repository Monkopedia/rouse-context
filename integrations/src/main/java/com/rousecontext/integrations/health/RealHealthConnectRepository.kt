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
import com.rousecontext.integrations.health.query.spanningBucketWidth
import java.time.Duration
import java.time.Instant
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transform
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
        val cap = limit ?: DEFAULT_MAX_RECORDS
        // Read one past the cap: enough to tell "the range fits" from "there is
        // more", and never more than that.
        val mapped = category.query(recordType, from, to, maxRecords = cap + 1)
        if (mapped.size <= cap) {
            return QueryResult.Records(mapped, totalCount = mapped.size, downsampled = false)
        }
        // The range holds more than the cap, so the records that fit are its
        // earliest slice, not a picture of it. Answer with per-window aggregates
        // spanning the whole range instead, at the resolution the cap implies.
        val width = spanningBucketWidth(from, to, buckets = minOf(cap, MAX_BUCKETS))
        return when (val bucketed = bucketRecords(recordType, from, to, width)) {
            is BucketResult.Success -> QueryResult.Buckets(
                bucketed.buckets,
                width,
                bucketed.totalCount,
                bucketed.truncated
            )
            // Not a bucketable type (session, multi-value, cumulative): there is
            // nothing to aggregate, so spread real records across the range.
            is BucketResult.Error -> {
                val all = category.query(recordType, from, to, maxRecords = MAX_RECORDS)
                QueryResult.Records(
                    records = downsampleEvenly(all, cap),
                    totalCount = all.size,
                    downsampled = true
                )
            }
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
        val aggregated = bucketize(values, from, bucket, maxValues = MAX_RECORDS)
        return BucketResult.Success(
            aggregated.buckets,
            aggregated.totalCount,
            aggregated.truncated
        )
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
 * Reads are paginated and looped over the response `pageToken` until exhausted
 * or the caller's cap is met. The cap reaches the request itself — page size is
 * the smaller of [READ_PAGE_SIZE] and what the caller still needs — so a small
 * cap costs a small read instead of materialising the whole range. A stream is
 * bounded by its collector instead, which ends collection once it has folded
 * enough.
 *
 * [fetchPage] is a seam so pagination can be unit-tested without a real client.
 */
internal class HealthConnectClientRecordReader(private val fetchPage: PageFetcher) : RecordReader {

    constructor(client: HealthConnectClient) : this(
        fetchPage = { request -> client.readRecords(request) }
    )

    override suspend fun <T : Record> read(
        type: KClass<T>,
        from: Instant,
        to: Instant,
        maxRecords: Int
    ): List<T> {
        val accumulated = mutableListOf<T>()
        pages(type, from, to, maxRecords).collect { accumulated += it }
        return accumulated.take(maxRecords)
    }

    override fun <T : Record> stream(type: KClass<T>, from: Instant, to: Instant): Flow<T> =
        pages(type, from, to, Int.MAX_VALUE).transform { page -> page.forEach { emit(it) } }

    /**
     * Pages of records, requesting no more per page than [maxRecords] still needs
     * and stopping once that many have been fetched. Cancelling collection stops
     * the read, so a collector that needs only the first few records pays for one
     * page.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Record> pages(
        type: KClass<T>,
        from: Instant,
        to: Instant,
        maxRecords: Int
    ): Flow<List<T>> = flow {
        val filter = TimeRangeFilter.between(from, to)
        var fetched = 0
        var pageToken: String? = null
        do {
            val response = fetchPage(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = filter,
                    pageSize = minOf(READ_PAGE_SIZE, maxRecords - fetched),
                    pageToken = pageToken
                )
            ) as ReadRecordsResponse<T>
            emit(response.records)
            fetched += response.records.size
            pageToken = response.pageToken
            // An empty page ends the read: without this a token that never
            // advances would loop forever.
        } while (pageToken != null && response.records.isNotEmpty() && fetched < maxRecords)
    }
}
