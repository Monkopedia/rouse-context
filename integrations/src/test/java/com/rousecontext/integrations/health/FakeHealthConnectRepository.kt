package com.rousecontext.integrations.health

import com.rousecontext.integrations.health.query.Bucket
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.JsonObject

/**
 * Test fake for [HealthConnectRepository].
 *
 * Populate [records] and [grantedPermissions] before calling query methods.
 * Set [shouldThrow] to simulate Health Connect being unavailable.
 */
class FakeHealthConnectRepository : HealthConnectRepository {

    /** Map of record type name to list of JSON records. */
    var records: MutableMap<String, List<JsonObject>> = mutableMapOf()

    /** Set of record type names that have permission granted. */
    var grantedPermissions: MutableSet<String> = mutableSetOf()

    /** Whether the historical data read permission is granted. */
    var historicalReadGranted: Boolean = false

    /** Canned summary response. */
    var summaryResponse: JsonObject = JsonObject(emptyMap())

    /** Captures the `from`/`to` passed to the most recent [getSummary] call. */
    var lastSummaryFrom: Instant? = null
    var lastSummaryTo: Instant? = null

    /** When non-null, all methods throw this exception. */
    var shouldThrow: Exception? = null

    /** Canned bucket results by record type. */
    var buckets: MutableMap<String, BucketResult> = mutableMapOf()

    /** Captures the last [bucketRecords] call for assertions. */
    var lastBucketCall: BucketCall? = null

    data class BucketCall(
        val recordType: String,
        val from: Instant,
        val to: Instant,
        val bucket: Duration
    )

    override suspend fun queryRecords(
        recordType: String,
        from: Instant,
        to: Instant,
        limit: Int?
    ): QueryResult {
        shouldThrow?.let { throw it }
        val all = records[recordType] ?: emptyList()
        val cap = limit ?: DEFAULT_MAX_RECORDS
        return if (all.size > cap) {
            QueryResult(records = all.take(cap), totalCount = all.size, downsampled = true)
        } else {
            QueryResult(records = all, totalCount = all.size, downsampled = false)
        }
    }

    override suspend fun bucketRecords(
        recordType: String,
        from: Instant,
        to: Instant,
        bucket: Duration
    ): BucketResult {
        shouldThrow?.let { throw it }
        lastBucketCall = BucketCall(recordType, from, to, bucket)
        return buckets[recordType] ?: BucketResult.Success(emptyList<Bucket>())
    }

    override suspend fun getGrantedPermissions(): Set<String> {
        shouldThrow?.let { throw it }
        return grantedPermissions.toSet()
    }

    override suspend fun isHistoricalReadGranted(): Boolean {
        shouldThrow?.let { throw it }
        return historicalReadGranted
    }

    override suspend fun getSummary(from: Instant, to: Instant): JsonObject {
        shouldThrow?.let { throw it }
        lastSummaryFrom = from
        lastSummaryTo = to
        return summaryResponse
    }
}
