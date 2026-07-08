package com.rousecontext.integrations.health

import com.rousecontext.integrations.health.query.CategoryQueries
import com.rousecontext.integrations.health.query.TimedValue
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        private val summaryValue: Long = 0L,
        private val bucketable: Map<String, List<TimedValue>> = emptyMap()
    ) : CategoryQueries {
        val queryCalls: MutableList<Triple<String, Instant, Instant>> = mutableListOf()
        var bucketValuesCalled = false

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

        override suspend fun bucketValues(
            recordType: String,
            from: Instant,
            to: Instant
        ): List<TimedValue>? {
            bucketValuesCalled = true
            return bucketable[recordType]
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

    private fun jsonRecords(n: Int): List<JsonObject> = (0 until n).map { i ->
        buildJsonObject { put("i", i) }
    }

    @Test
    fun `queryRecords under cap returns all with downsampled false`() = runBlocking {
        val cat = RecordingCategory(setOf("BloodGlucose"), queryResponse = jsonRecords(10))
        val repo = make(listOf(cat))

        val result = repo.queryRecords("BloodGlucose", from, to, limit = 100)
        assertEquals(10, result.records.size)
        assertEquals(10, result.totalCount)
        assertTrue(!result.downsampled)
    }

    @Test
    fun `queryRecords over cap downsamples to cap keeping first and last`() = runBlocking {
        val cat = RecordingCategory(setOf("BloodGlucose"), queryResponse = jsonRecords(100))
        val repo = make(listOf(cat))

        val result = repo.queryRecords("BloodGlucose", from, to, limit = 10)
        assertEquals(10, result.records.size)
        assertEquals(100, result.totalCount)
        assertTrue(result.downsampled)
        assertEquals(0, result.records.first()["i"]!!.jsonPrimitive.content.toInt())
        assertEquals(99, result.records.last()["i"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `queryRecords uses default cap when limit null`() = runBlocking {
        val cat = RecordingCategory(
            setOf("BloodGlucose"),
            queryResponse = jsonRecords(DEFAULT_MAX_RECORDS + 50)
        )
        val repo = make(listOf(cat))

        val result = repo.queryRecords("BloodGlucose", from, to, limit = null)
        assertEquals(DEFAULT_MAX_RECORDS, result.records.size)
        assertTrue(result.downsampled)
    }

    @Test
    fun `bucketRecords aggregates scalar values per window`() = runBlocking {
        val values = listOf(
            TimedValue(from.plusSeconds(60), 5.0),
            TimedValue(from.plusSeconds(120), 7.0),
            TimedValue(from.plusSeconds(3660), 10.0)
        )
        val cat = RecordingCategory(
            setOf("BloodGlucose"),
            bucketable = mapOf("BloodGlucose" to values)
        )
        val repo = make(listOf(cat))

        val result = repo.bucketRecords(
            "BloodGlucose",
            from,
            from.plusSeconds(7200),
            Duration.ofHours(1)
        )
        result as BucketResult.Success
        assertEquals(2, result.buckets.size)
        assertEquals(6.0, result.buckets[0].avg, 0.0001)
        assertEquals(10.0, result.buckets[1].avg, 0.0001)
    }

    @Test
    fun `bucketRecords rejects too-fine bucket before reading`() = runBlocking {
        val cat = RecordingCategory(
            setOf("BloodGlucose"),
            bucketable = mapOf("BloodGlucose" to emptyList())
        )
        val repo = make(listOf(cat))

        // 1 minute buckets over 100 days => way over MAX_BUCKETS.
        val wideTo = from.plus(Duration.ofDays(100))
        val result = repo.bucketRecords("BloodGlucose", from, wideTo, Duration.ofMinutes(1))
        result as BucketResult.Error
        assertTrue(result.message.contains("$MAX_BUCKETS"))
        assertTrue("must not read", !cat.bucketValuesCalled)
    }

    @Test
    fun `bucketRecords rejects non-bucketable type`() = runBlocking {
        val cat = RecordingCategory(setOf("SleepSession")) // no bucketable map => null
        val repo = make(listOf(cat))

        val result = repo.bucketRecords("SleepSession", from, to, Duration.ofDays(1))
        result as BucketResult.Error
        assertTrue(result.message.contains("not supported"))
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
