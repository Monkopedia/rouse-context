package com.rousecontext.integrations.health.query

import androidx.health.connect.client.records.Record
import com.rousecontext.integrations.health.MAX_RECORDS
import com.rousecontext.integrations.health.STREAM_MAX_RECORDS
import java.time.Instant
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * Seam for reading Health Connect records.
 *
 * Abstracts the Health Connect SDK call so per-category query classes can be
 * tested with a fake reader that returns canned records.
 */
interface RecordReader {

    /**
     * Read at most [maxRecords] records of [type] in the given range.
     *
     * The bound reaches the Health Connect request itself (page size and number
     * of pages), so a small cap costs a small read rather than materialising the
     * whole range and trimming afterwards.
     */
    suspend fun <T : Record> read(
        type: KClass<T>,
        from: Instant,
        to: Instant,
        maxRecords: Int
    ): List<T>

    /**
     * Stream every record of [type] in the range, one page at a time.
     *
     * Collectors see records without the whole range ever being held in memory,
     * which is what lets a wide window be aggregated into buckets. Ending
     * collection stops the read, so `take(n)` costs only the pages it needed, and
     * in practice the collector is what stops it first — see [bucketize]'s
     * `maxValues`. That cap counts samples, though, so a record yielding none
     * would never trip it; implementations MUST also stop at
     * [STREAM_MAX_RECORDS] records so such a range terminates.
     */
    fun <T : Record> stream(type: KClass<T>, from: Instant, to: Instant): Flow<T>
}

/**
 * Shared query helper that eliminates per-record-type boilerplate.
 *
 * Reads at most [maxRecords] records of [type] in the given time range, applies
 * [mapper] to produce JSON objects (one-to-many, supporting both map and flatMap
 * use cases), and optionally sorts by the `"time"` key when [sortByTime] is true.
 *
 * [maxRecords] bounds the *read*; the mapped result may be longer for types whose
 * records carry several samples each (e.g. HeartRate). Callers that need a bounded
 * result compare the mapped size against their own cap.
 */
internal suspend fun <T : Record> RecordReader.queryRecords(
    type: KClass<T>,
    from: Instant,
    to: Instant,
    maxRecords: Int = MAX_RECORDS,
    sortByTime: Boolean = false,
    mapper: (T) -> List<JsonObject>
): List<JsonObject> {
    val mapped = read(type, from, to, maxRecords).flatMap(mapper)
    return if (sortByTime) mapped.sortedBy { it["time"].toString() } else mapped
}
