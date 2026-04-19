package com.rousecontext.app.integration.harness

import com.rousecontext.integrations.health.HealthConnectRepository
import java.time.Instant
import kotlinx.serialization.json.JsonObject

/**
 * Test fake for [HealthConnectRepository].
 *
 * Source of truth still lives at
 * `integrations/src/test/java/com/rousecontext/integrations/health/FakeHealthConnectRepository.kt`,
 * but that source set is not published so `:app`'s test compiler cannot see
 * it. Until someone factors out a shared `:core:testfixtures-android`
 * module (pretty much the same move we did for `TestRelayFixture` on the
 * JVM side), this file is a byte-for-byte copy so the harness can wire a
 * fake repository into the override module.
 *
 * Issue #250. Consolidation tracked alongside the rest of the test-fixture
 * refactoring in #251+.
 */
class HarnessFakeHealthConnectRepository : HealthConnectRepository {

    /** Map of record type name to list of JSON records. */
    var records: MutableMap<String, List<JsonObject>> = mutableMapOf()

    /** Set of record type names that have permission granted. */
    var grantedPermissions: MutableSet<String> = mutableSetOf()

    /** Whether the historical data read permission is granted. */
    var historicalReadGranted: Boolean = false

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

    override suspend fun isHistoricalReadGranted(): Boolean {
        shouldThrow?.let { throw it }
        return historicalReadGranted
    }

    override suspend fun getSummary(from: Instant, to: Instant): JsonObject {
        shouldThrow?.let { throw it }
        return summaryResponse
    }
}
