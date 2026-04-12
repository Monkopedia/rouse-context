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
