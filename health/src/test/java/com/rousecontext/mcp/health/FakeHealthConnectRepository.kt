package com.rousecontext.mcp.health

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

    /** Canned summary response. */
    var summaryResponse: JsonObject = JsonObject(emptyMap())

    /** When non-null, all methods throw this exception. */
    var shouldThrow: Exception? = null

    override suspend fun queryRecords(
        recordType: String,
        from: Instant,
        to: Instant,
        limit: Int?
    ): List<JsonObject> {
        shouldThrow?.let { throw it }
        val all = records[recordType] ?: emptyList()
        return if (limit != null) all.take(limit) else all
    }

    override suspend fun getGrantedPermissions(): Set<String> {
        shouldThrow?.let { throw it }
        return grantedPermissions.toSet()
    }

    override suspend fun getSummary(from: Instant, to: Instant): JsonObject {
        shouldThrow?.let { throw it }
        return summaryResponse
    }
}
