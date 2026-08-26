package com.rousecontext.integrations.health

import com.rousecontext.integrations.health.query.Bucket
import java.time.Duration
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
     * The read is bounded by the cap ([limit] if given, else
     * [DEFAULT_MAX_RECORDS]) — the cap reaches the Health Connect request rather
     * than being applied to a fully materialised range.
     *
     * When the range holds more than the cap, returning the records that fit
     * would return its *earliest slice*, which is not an answer about the range.
     * So the query is instead answered with [QueryResult.Buckets]: per-window
     * count/min/max/avg across the range, streamed and folded so the range is
     * never materialised. A range holding more than [MAX_RECORDS] samples folds
     * only the earliest [MAX_RECORDS] of them and says so via
     * [QueryResult.Buckets.truncated]. Record types that cannot be bucketed
     * (sessions, multi-value, cumulative) fall back to [QueryResult.Records]
     * evenly spread across the range with `downsampled` set.
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
        bucket: Duration
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
 *  - [Records] carries raw record objects, wire-compatible with the pre-existing
 *    per-type JSON shape.
 *  - [Buckets] carries per-window aggregates, used when the range holds more raw
 *    records than the cap so that the answer spans the range instead of being its
 *    earliest slice.
 */
sealed class QueryResult {

    /**
     * @param records the record objects, evenly spread across the range when
     *   [downsampled] is set
     * @param totalCount number of mapped records before any downsampling
     * @param downsampled true when [records] is an evenly-spread subset
     */
    data class Records(
        val records: List<JsonObject>,
        val totalCount: Int,
        val downsampled: Boolean
    ) : QueryResult()

    /**
     * @param buckets per-window count/min/max/avg, ordered by start time
     * @param width the window width the range was divided into
     * @param totalCount number of samples aggregated
     * @param truncated true when aggregation stopped at [MAX_RECORDS] samples, so
     *   [buckets] cover only the earliest part of the requested range
     */
    data class Buckets(
        val buckets: List<Bucket>,
        val width: Duration,
        val totalCount: Int,
        val truncated: Boolean
    ) : QueryResult()
}

/**
 * Result of [HealthConnectRepository.bucketRecords].
 *
 *  - [Success] carries the aggregated [buckets].
 *  - [Error] carries a human-readable message (bad bucket, too-fine bucket over a
 *    large range, or a non-bucketable record type). Callers surface it verbatim.
 */
sealed class BucketResult {

    /**
     * @param truncated true when aggregation stopped at [MAX_RECORDS] samples, so
     *   [buckets] cover only the earliest part of the requested range
     */
    data class Success(
        val buckets: List<Bucket>,
        val totalCount: Int,
        val truncated: Boolean = false
    ) : BucketResult()

    data class Error(val message: String) : BucketResult()
}

/**
 * Upper bound on records a single read will accumulate, and on samples a single
 * aggregation will fold, to bound work. Hitting it while aggregating is reported
 * (`truncated`) rather than passed off as whole-range coverage.
 *
 * It does not bound a streamed read directly: a stream carries records, and the
 * fold that consumes them counts samples. Records normally yield at least one
 * sample each, so the sample cap trips first and the stream ends within a page of
 * this many records — but a record that yields none would not trip it at all,
 * which is what [STREAM_MAX_RECORDS] exists to stop.
 */
const val MAX_RECORDS: Int = 50_000

/** Default cap on raw records returned by a query when the caller gives no limit. */
const val DEFAULT_MAX_RECORDS: Int = 500

/**
 * Health Connect page size for paginated reads.
 *
 * This deliberately pins `ReadRecordsRequest`'s own default rather than picking a
 * smaller number: a Pixel 6 Pro read real Health Connect cleanly at page sizes up
 * to 3000 (issue #545), so shrinking it to stay under the Binder transaction
 * limit would be a speculative fix for a mechanism that was measured not to
 * exist, at the cost of tripling the round-trips. `PaginatedReaderTest` asserts
 * the pinning, so a change in the library default surfaces as a failing test
 * rather than a silent drift. A caller's own cap still shrinks the page below
 * this — see [com.rousecontext.integrations.health.query.RecordReader.read].
 */
const val READ_PAGE_SIZE: Int = 1000

/**
 * Hard ceiling on records a single streamed read will fetch.
 *
 * One page of slack over [MAX_RECORDS], so it never cuts short a fold that the
 * sample cap would have satisfied, and it still terminates a range of records
 * that yield no samples at all.
 */
const val STREAM_MAX_RECORDS: Int = MAX_RECORDS + READ_PAGE_SIZE

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
