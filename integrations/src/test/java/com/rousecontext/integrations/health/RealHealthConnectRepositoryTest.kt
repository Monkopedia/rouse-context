package com.rousecontext.integrations.health

import com.rousecontext.integrations.health.query.CategoryQueries
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests the thin dispatch layer of [RealHealthConnectRepository]:
 * routing of [queryRecords] to the owning category, and composition of
 * [getSummary] across all categories.
 *
 * Per-record-type JSON shape is covered by the individual category tests.
 */
class RealHealthConnectRepositoryTest {

    private val from: Instant = Instant.parse("2026-04-01T00:00:00Z")
    private val to: Instant = Instant.parse("2026-04-30T00:00:00Z")

    private class RecordingCategory(
        override val recordTypes: Set<String>,
        private val queryResponse: List<JsonObject> = emptyList(),
        private val summaryKey: String? = null,
        private val summaryValue: Long = 0L
    ) : CategoryQueries {
        val queryCalls: MutableList<Triple<String, Instant, Instant>> = mutableListOf()

        override suspend fun query(
            recordType: String,
            from: Instant,
            to: Instant,
            limit: Int?
        ): List<JsonObject> {
            queryCalls += Triple(recordType, from, to)
            return queryResponse
        }

        override suspend fun summary(from: Instant, to: Instant, granted: Set<String>): JsonObject =
            buildJsonObject {
                if (summaryKey != null && recordTypes.any { it in granted }) {
                    put(summaryKey, summaryValue)
                }
            }
    }

    private fun make(
        categories: List<CategoryQueries>,
        granted: Set<String> = emptySet(),
        historical: Boolean = false
    ): RealHealthConnectRepository = RealHealthConnectRepository(
        categoriesProvider = { categories },
        grantedPermissionsProvider = { granted },
        historicalReadGrantedProvider = { historical }
    )

    @Test
    fun `queryRecords dispatches to the category owning the record type`() = runBlocking {
        val activity = RecordingCategory(setOf("Steps", "Distance"))
        val body = RecordingCategory(setOf("Weight"))
        val repo = make(listOf(activity, body))

        repo.queryRecords("Weight", from, to, null)
        repo.queryRecords("Distance", from, to, 10)

        assertEquals(1, body.queryCalls.size)
        assertEquals("Weight", body.queryCalls[0].first)

        assertEquals(1, activity.queryCalls.size)
        assertEquals("Distance", activity.queryCalls[0].first)
    }

    @Test
    fun `queryRecords throws for unknown record type`() {
        val repo = make(listOf(RecordingCategory(setOf("Steps"))))
        try {
            runBlocking { repo.queryRecords("NotARecordType", from, to, null) }
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun `queryRecords throws when record type known to registry but no category handles it`() {
        // Steps is in the registry but our categories list doesn't include it.
        val repo = make(listOf(RecordingCategory(setOf("Weight"))))
        try {
            runBlocking { repo.queryRecords("Steps", from, to, null) }
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun `getSummary composes fields from every category that contributes`() = runBlocking {
        val activity = RecordingCategory(
            recordTypes = setOf("Steps"),
            summaryKey = "steps_total",
            summaryValue = 123L
        )
        val vitals = RecordingCategory(
            recordTypes = setOf("HeartRate"),
            summaryKey = "avg_heart_rate",
            summaryValue = 72L
        )
        val empty = RecordingCategory(setOf("MindfulnessSession"))
        val repo = make(
            listOf(activity, vitals, empty),
            granted = setOf("Steps", "HeartRate", "MindfulnessSession")
        )

        val summary = repo.getSummary(from, to)
        assertEquals("123", summary["steps_total"]!!.jsonPrimitive.content)
        assertEquals("72", summary["avg_heart_rate"]!!.jsonPrimitive.content)
    }

    @Test
    fun `getSummary omits fields from categories whose record types are not granted`() =
        runBlocking {
            val activity = RecordingCategory(
                recordTypes = setOf("Steps"),
                summaryKey = "steps_total",
                summaryValue = 999L
            )
            val vitals = RecordingCategory(
                recordTypes = setOf("HeartRate"),
                summaryKey = "avg_heart_rate",
                summaryValue = 60L
            )
            val repo = make(listOf(activity, vitals), granted = setOf("HeartRate"))

            val summary = repo.getSummary(from, to)
            assertNull(summary["steps_total"])
            assertNotNull(summary["avg_heart_rate"])
        }

    @Test
    fun `getGrantedPermissions returns permissions from provider`() = runBlocking {
        val repo = make(
            categories = listOf(RecordingCategory(setOf("Steps"))),
            granted = setOf("Steps", "HeartRate")
        )
        assertEquals(setOf("Steps", "HeartRate"), repo.getGrantedPermissions())
    }

    @Test
    fun `isHistoricalReadGranted returns provider value`() = runBlocking {
        assertEquals(
            true,
            make(listOf(RecordingCategory(setOf("Steps"))), historical = true)
                .isHistoricalReadGranted()
        )
        assertEquals(
            false,
            make(listOf(RecordingCategory(setOf("Steps"))), historical = false)
                .isHistoricalReadGranted()
        )
    }
}
