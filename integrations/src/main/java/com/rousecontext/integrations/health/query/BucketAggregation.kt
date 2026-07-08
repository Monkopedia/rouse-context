package com.rousecontext.integrations.health.query

import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A single scalar health value at a point in time. Produced by
 * [CategoryQueries.bucketValues] for bucketable (instantaneous scalar) record
 * types and consumed by [bucketize].
 */
data class TimedValue(val time: Instant, val value: Double)

/**
 * Aggregate stats for one time bucket: the number of samples and the min / max /
 * average of their scalar values. `start` is the (inclusive) start instant of
 * the bucket, aligned to the query's `from`.
 */
data class Bucket(
    val start: Instant,
    val count: Int,
    val min: Double,
    val max: Double,
    val avg: Double
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("start", start.toString())
        put("count", count)
        put("min", min)
        put("max", max)
        put("avg", avg)
    }
}

/**
 * Group [values] into fixed-width [bucket] windows aligned to [from], emitting a
 * [Bucket] per non-empty window (empty windows are skipped). Buckets are ordered
 * by start time.
 */
fun bucketize(values: List<TimedValue>, from: Instant, bucket: Duration): List<Bucket> {
    if (values.isEmpty()) return emptyList()
    val width = bucket.toMillis()
    val fromMillis = from.toEpochMilli()
    val byIndex = sortedMapOf<Long, MutableList<Double>>()
    for (tv in values) {
        val offset = tv.time.toEpochMilli() - fromMillis
        val index = if (offset < 0) 0L else offset / width
        byIndex.getOrPut(index) { mutableListOf() }.add(tv.value)
    }
    return byIndex.map { (index, vals) ->
        Bucket(
            start = Instant.ofEpochMilli(fromMillis + index * width),
            count = vals.size,
            min = vals.min(),
            max = vals.max(),
            avg = vals.average()
        )
    }
}

/**
 * Evenly downsample [items] to at most [cap] entries, preserving order and always
 * retaining the first and last element. Used to bound raw-record responses for
 * high-volume record types (e.g. CGM blood glucose) while keeping coverage spread
 * across the whole time range rather than clustered at one end.
 */
fun <T> downsampleEvenly(items: List<T>, cap: Int): List<T> {
    require(cap > 0) { "cap must be positive" }
    if (items.size <= cap) return items
    if (cap == 1) return listOf(items.first())
    val n = items.size
    val result = ArrayList<T>(cap)
    // Map i in [0, cap-1] onto [0, n-1] so first and last are always included.
    for (i in 0 until cap) {
        val idx = (i.toLong() * (n - 1) / (cap - 1)).toInt()
        result.add(items[idx])
    }
    return result
}
