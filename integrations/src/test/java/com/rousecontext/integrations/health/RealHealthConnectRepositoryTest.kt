package com.rousecontext.integrations.health

import com.rousecontext.integrations.health.query.CategoryQueries
import com.rousecontext.integrations.health.query.Streamed
import com.rousecontext.integrations.health.query.TimedValue
import com.rousecontext.integrations.health.query.streamed
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
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
        private val bucketable: Map<String, Flow<Streamed<TimedValue>>> = emptyMap()
    ) : CategoryQueries {
        val queryCalls: MutableList<QueryCall> = mutableListOf()
        var bucketValuesCalled = false

        data class QueryCall(
            val recordType: String,
            val from: Instant,
            val to: Instant,
            val maxRecords: Int
        )

        override suspend fun query(
            recordType: String,
            from: Instant,
            to: Instant,
            maxRecords: Int
        ): List<JsonObject> {
            queryCalls += QueryCall(recordType, from, to, maxRecords)
            // Behave like the real reader: never hand back more than was asked for.
            return queryResponse.take(maxRecords)
        }

        override suspend fun summary(from: Instant, to: Instant, granted: Set<String>): JsonObject =
            buildJsonObject {
                if (summaryKey != null && recordTypes.any { it in granted }) {
                    put(summaryKey, summaryValue)
                }
            }

        override fun bucketValues(
            recordType: String,
            from: Instant,
            to: Instant
        ): Flow<Streamed<TimedValue>>? {
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
        assertEquals("Weight", body.queryCalls[0].recordType)

        assertEquals(1, activity.queryCalls.size)
        assertEquals("Distance", activity.queryCalls[0].recordType)
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
    fun `queryRecords under cap returns all records and reads only the cap`() = runBlocking {
        val cat = RecordingCategory(setOf("BloodGlucose"), queryResponse = jsonRecords(10))
        val repo = make(listOf(cat))

        val result = repo.queryRecords("BloodGlucose", from, to, limit = 100)
        result as QueryResult.Records
        assertEquals(10, result.records.size)
        assertEquals(10, result.totalCount)
        assertTrue(!result.downsampled)
        // One bounded read, sized to the cap (+1, to tell "fits" from "there is more").
        assertEquals(1, cat.queryCalls.size)
        assertEquals(101, cat.queryCalls[0].maxRecords)
    }

    @Test
    fun `queryRecords over a spanning window returns buckets covering the whole range`() =
        runBlocking {
            // 2000 samples evenly spread across the window: far more than the cap.
            val count = 2000
            val step = Duration.between(from, to).toMillis() / count
            val values = (0 until count).map {
                TimedValue(from.plusMillis(it * step), it.toDouble())
            }
            val cat = RecordingCategory(
                setOf("BloodGlucose"),
                queryResponse = jsonRecords(count),
                bucketable = mapOf("BloodGlucose" to values.asFlow().streamed())
            )
            val repo = make(listOf(cat))

            val result = repo.queryRecords("BloodGlucose", from, to, limit = 100)

            assertTrue(
                "a spanning query must be answered with aggregates across the range, not " +
                    "its earliest slice; got ${result::class.simpleName}",
                result is QueryResult.Buckets
            )
            result as QueryResult.Buckets
            assertTrue("the whole range was folded, so nothing is truncated", !result.truncated)
            assertEquals(count, result.totalCount)
            assertTrue("must produce buckets", result.buckets.size > 1)

            // The answer must be a picture of the WHOLE window, not its earliest slice.
            val firstStart = result.buckets.first().start
            val lastStart = result.buckets.last().start
            assertTrue(
                "first bucket must start at the window start, was $firstStart",
                !firstStart.isBefore(from) && firstStart.isBefore(from.plus(result.width))
            )
            assertTrue(
                "last bucket must reach the window end, was $lastStart",
                !lastStart.isBefore(to.minus(result.width.multipliedBy(2)))
            )
            // and it must carry the values from the far end of the window
            assertEquals((count - 1).toDouble(), result.buckets.last().max, 0.0001)
        }

    @Test
    fun `queryRecords reports truncation when aggregation hits its sample ceiling`() = runBlocking {
        // Twice the ceiling, generated lazily: the fold must stop and say so
        // rather than presenting the earliest half as the whole range.
        val step = Duration.between(from, to).toMillis() / (MAX_RECORDS * 2L)
        val values = flow {
            for (i in 0 until MAX_RECORDS * 2) {
                emit(TimedValue(from.plusMillis(i * step), i.toDouble()))
            }
        }
        val cat = RecordingCategory(
            setOf("BloodGlucose"),
            queryResponse = jsonRecords(600),
            bucketable = mapOf("BloodGlucose" to values.streamed())
        )
        val repo = make(listOf(cat))

        val result = repo.queryRecords("BloodGlucose", from, to, limit = 100)
        result as QueryResult.Buckets
        assertTrue("must report that the ceiling stopped the fold", result.truncated)
        assertEquals(MAX_RECORDS, result.totalCount)
    }

    @Test
    fun `queryRecords does not materialise the window when routing to buckets`() = runBlocking {
        val count = 2000
        val step = Duration.between(from, to).toMillis() / count
        val values = (0 until count).map { TimedValue(from.plusMillis(it * step), it.toDouble()) }
        val cat = RecordingCategory(
            setOf("BloodGlucose"),
            queryResponse = jsonRecords(count),
            bucketable = mapOf("BloodGlucose" to values.asFlow().streamed())
        )
        val repo = make(listOf(cat))

        repo.queryRecords("BloodGlucose", from, to, limit = 50)

        assertEquals(
            "only the bounded probe read; the spread comes from streamed aggregation",
            1,
            cat.queryCalls.size
        )
        assertEquals(51, cat.queryCalls[0].maxRecords)
    }

    @Test
    fun `queryRecords spreads records across the range for a non-bucketable overflow`() =
        runBlocking {
            // No bucketable values => cannot aggregate; must still span the range.
            val cat = RecordingCategory(setOf("SleepSession"), queryResponse = jsonRecords(100))
            val repo = make(listOf(cat))

            val result = repo.queryRecords("SleepSession", from, to, limit = 10)
            result as QueryResult.Records
            assertEquals(10, result.records.size)
            assertEquals(100, result.totalCount)
            assertTrue(result.downsampled)
            assertEquals(0, result.records.first()["i"]!!.jsonPrimitive.content.toInt())
            assertEquals(99, result.records.last()["i"]!!.jsonPrimitive.content.toInt())
        }

    @Test
    fun `queryRecords uses the default cap when limit is null`() = runBlocking {
        val cat = RecordingCategory(
            setOf("SleepSession"),
            queryResponse = jsonRecords(DEFAULT_MAX_RECORDS + 50)
        )
        val repo = make(listOf(cat))

        val result = repo.queryRecords("SleepSession", from, to, limit = null)
        result as QueryResult.Records
        assertEquals(DEFAULT_MAX_RECORDS, result.records.size)
        assertTrue(result.downsampled)
        assertEquals(DEFAULT_MAX_RECORDS + 1, cat.queryCalls[0].maxRecords)
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
            bucketable = mapOf("BloodGlucose" to values.asFlow().streamed())
        )
        val repo = make(listOf(cat))

        val result = repo.bucketRecords(
            "BloodGlucose",
            from,
            from.plusSeconds(7200),
            Duration.ofHours(1)
        )
        result as BucketResult.Success
        assertEquals(3, result.totalCount)
        assertEquals(2, result.buckets.size)
        assertEquals(6.0, result.buckets[0].avg, 0.0001)
        assertEquals(10.0, result.buckets[1].avg, 0.0001)
    }

    @Test
    fun `bucketRecords rejects too-fine bucket before reading`() = runBlocking {
        val cat = RecordingCategory(
            setOf("BloodGlucose"),
            bucketable = mapOf("BloodGlucose" to emptyFlow<TimedValue>().streamed())
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
