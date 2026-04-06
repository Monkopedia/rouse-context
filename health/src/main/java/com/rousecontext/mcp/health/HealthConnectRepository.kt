package com.rousecontext.mcp.health

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
     * @param recordType one of the names in [RecordTypeRegistry], e.g. "Steps"
     * @param from start of time range (inclusive)
     * @param to end of time range (exclusive)
     * @param limit max records to return, or null for all
     * @return list of JSON objects, each representing one record with type-specific fields
     * @throws IllegalArgumentException if [recordType] is not recognized
     */
    suspend fun queryRecords(
        recordType: String,
        from: Instant,
        to: Instant,
        limit: Int? = null
    ): List<JsonObject>

    /**
     * Check which record types currently have read permission granted.
     *
     * @return set of record type names (matching [RecordTypeRegistry] keys) that are permitted
     */
    suspend fun getGrantedPermissions(): Set<String>

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
 * Thrown when the Health Connect SDK is not available on this device.
 */
class HealthConnectUnavailableException(
    message: String = "Health Connect is not available on this device"
) : RuntimeException(message)
