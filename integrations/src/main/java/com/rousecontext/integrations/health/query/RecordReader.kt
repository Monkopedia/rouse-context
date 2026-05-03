package com.rousecontext.integrations.health.query

import androidx.health.connect.client.records.Record
import java.time.Instant
import kotlin.reflect.KClass
import kotlinx.serialization.json.JsonObject

/**
 * Seam for reading Health Connect records.
 *
 * Abstracts the Health Connect SDK call so per-category query classes can be
 * tested with a fake reader that returns canned records.
 */
interface RecordReader {
    suspend fun <T : Record> read(type: KClass<T>, from: Instant, to: Instant): List<T>
}

/**
 * Shared query helper that eliminates per-record-type boilerplate.
 *
 * Reads records of [type] in the given time range, applies [mapper] to produce
 * JSON objects (one-to-many, supporting both map and flatMap use cases), optionally
 * sorts by the `"time"` key when [sortByTime] is true, and applies [limit].
 */
internal suspend fun <T : Record> RecordReader.queryRecords(
    type: KClass<T>,
    from: Instant,
    to: Instant,
    limit: Int?,
    sortByTime: Boolean = false,
    mapper: (T) -> List<JsonObject>
): List<JsonObject> {
    val records = read(type, from, to)
    val mapped = records.flatMap(mapper)
    val sorted = if (sortByTime) mapped.sortedBy { it["time"].toString() } else mapped
    return if (limit != null) sorted.take(limit) else sorted
}
