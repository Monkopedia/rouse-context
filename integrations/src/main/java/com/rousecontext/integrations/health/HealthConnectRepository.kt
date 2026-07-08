package com.rousecontext.integrations.health

import com.rousecontext.integrations.health.query.Bucket
import java.time.Instant
import kotlinx.serialization.json.JsonObject

/**
 * Abstraction over the Health Connect SDK for reading health data.
 *
 * The production implementation lives in `:app` (requires Android Context).
 * Tests use a fake that returns canned data.
 */
interface HealthConnectRepository {

    /**
     * Query records of the given [recordType] within the time range.
     *
     * The underlying read is paginated and bounded, so high-volume types (e.g.
     * BloodGlucose from a CGM) no longer overflow the Health Connect Binder
     * transaction limit. When the number of mapped records exceeds the cap
     * ([limit] if given, else a default), the result is evenly downsampled across
     * the whole range — real records are kept, spread out — and
     * [QueryResult.downsampled] is set with the pre-downsample
     * [QueryResult.totalCount].
     *
     * @param recordType one of the names in [RecordTypeRegistry], e.g. "Steps"
     * @param from start of time range (inclusive)
     * @param to end of time range (exclusive)
     * @param limit cap on records to return, or null for the default cap
     * @throws IllegalArgumentException if [recordType] is not recognized
     */
    suspend fun queryRecords(
        recordType: String,
        from: Instant,
        to: Instant,
        limit: Int? = null
    ): QueryResult

    /**
     * Aggregate the scalar value of [recordType] into fixed-width [bucket] windows
     * aligned to [from], returning per-bucket count/min/max/avg.
     *
     * Only supported for instantaneous single-scalar record types (e.g.
     * BloodGlucose, HeartRate). Rejects (without reading) when the expected bucket
     * count exceeds the internal limit, when [recordType] is not bucketable, or
     * when [recordType] is unknown.
     */
    suspend fun bucketRecords(
        recordType: String,
        from: Instant,
        to: Instant,
        bucket: java.time.Duration
    ): BucketResult

    /**
     * Check which record types currently have read permission granted.
     *
     * @return set of record type names (matching [RecordTypeRegistry] keys) that are permitted
     */
    suspend fun getGrantedPermissions(): Set<String>

    /**
     * Check whether the user has granted permission to read historical health data
     * (data recorded before the app was installed/granted access).
     *
     * Corresponds to `HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY`
     * (i.e. `android.permission.health.READ_HEALTH_DATA_HISTORY`).
     */
    suspend fun isHistoricalReadGranted(): Boolean

    /**
     * Aggregate summary across record types for the given time range.
     *
     * Returns a JSON object with keys like "steps_total", "avg_heart_rate",
     * "sleep_hours", "weight_latest", etc. Only includes types that have
     * permission and data.
     */
    suspend fun getSummary(from: Instant, to: Instant): JsonObject
}

/**
 * Result of [HealthConnectRepository.queryRecords].
 *
 * @param records the (possibly downsampled) record objects, wire-compatible with
 *   the pre-existing per-type JSON shape
 * @param totalCount number of mapped records before any downsampling
 * @param downsampled true when [records] is an evenly-spread subset of the full set
 */
data class QueryResult(val records: List<JsonObject>, val totalCount: Int, val downsampled: Boolean)

/**
 * Result of [HealthConnectRepository.bucketRecords].
 *
 *  - [Success] carries the aggregated [buckets].
 *  - [Error] carries a human-readable message (bad bucket, too-fine bucket over a
 *    large range, or a non-bucketable record type). Callers surface it verbatim.
 */
sealed class BucketResult {
    data class Success(val buckets: List<Bucket>) : BucketResult()

    data class Error(val message: String) : BucketResult()
}

/** Upper bound on records accumulated by a single paginated read, to bound memory. */
const val MAX_RECORDS: Int = 50_000

/** Default cap on raw records returned by a query when the caller gives no limit. */
const val DEFAULT_MAX_RECORDS: Int = 500

/** Health Connect page size for paginated reads, safely under the Binder limit. */
const val READ_PAGE_SIZE: Int = 1000

/** Upper bound on the number of buckets a single bucketed query may produce. */
const val MAX_BUCKETS: Int = 1000

/**
 * Health Connect permission granting access to historical data
 * (records written before the app was installed or granted access).
 */
const val HEALTH_DATA_HISTORY_PERMISSION: String =
    "android.permission.health.READ_HEALTH_DATA_HISTORY"

/**
 * Thrown when the Health Connect SDK is not available on this device.
 */
class HealthConnectUnavailableException(
    message: String = "Health Connect is not available on this device"
) : RuntimeException(message)
