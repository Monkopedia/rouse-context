package com.rousecontext.integrations.health.query

import java.time.Instant
import kotlinx.serialization.json.JsonObject

/**
 * Contract for a per-category query class.
 *
 * Each implementation handles the record types belonging to a single
 * [com.rousecontext.integrations.health.RecordCategory] and knows how to:
 *  - translate a `record_type` name + time range into a list of JSON records
 *    preserving the existing wire format exactly, and
 *  - contribute fields to the aggregate health summary when permissions allow.
 */
interface CategoryQueries {

    /** Record type names this category handles, matching `RecordTypeRegistry` keys. */
    val recordTypes: Set<String>

    /**
     * Query records of [recordType] within the time range.
     *
     * @throws IllegalArgumentException if [recordType] is not handled by this category.
     */
    suspend fun query(recordType: String, from: Instant, to: Instant, limit: Int?): List<JsonObject>

    /**
     * Contribute summary fields for this category. Implementations MUST only
     * include record types present in [granted]. Returns an empty JSON object
     * when the category contributes nothing for the given inputs.
     */
    suspend fun summary(from: Instant, to: Instant, granted: Set<String>): JsonObject

    /**
     * Extract the per-record scalar values for [recordType] over the time range,
     * for bucketed aggregation. Returns `null` when [recordType] is not a
     * bucketable (instantaneous single-scalar) type — e.g. sessions, multi-value,
     * or cumulative types — in which case the caller reports a clear error.
     *
     * The default returns `null`; categories that own scalar types override this.
     */
    suspend fun bucketValues(recordType: String, from: Instant, to: Instant): List<TimedValue>? =
        null
}
