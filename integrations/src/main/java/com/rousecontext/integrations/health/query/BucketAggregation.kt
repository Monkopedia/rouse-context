package com.rousecontext.integrations.health.query

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.take
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
 * [buckets] plus the number of samples they were folded from.
 *
 * @param truncated true when the fold stopped at its sample ceiling, so [buckets]
 *   cover only the earliest part of the requested range. Callers must not claim
 *   whole-range coverage when this is set.
 */
data class BucketedValues(val buckets: List<Bucket>, val totalCount: Int, val truncated: Boolean)

/**
 * Fold [values] into fixed-width [width] windows aligned to [from], emitting a
 * [Bucket] per non-empty window (empty windows are skipped). Buckets are ordered
 * by start time.
 *
 * Values are folded as they arrive, so aggregating a range costs memory
 * proportional to the number of buckets rather than to the number of records in
 * it — the whole point of answering a wide window with aggregates.
 *
 * At most [maxValues] are folded; hitting that ceiling stops collection (and so
 * the underlying read) and sets [BucketedValues.truncated], because the buckets
 * then describe only the earliest part of the range.
 */
suspend fun bucketize(
    values: Flow<TimedValue>,
    from: Instant,
    width: Duration,
    maxValues: Int
): BucketedValues {
    val widthMillis = width.toMillis()
    val fromMillis = from.toEpochMilli()
    val byIndex = sortedMapOf<Long, Accumulator>()
    var total = 0
    var truncated = false
    // One past the ceiling: enough to tell "that was all of it" from "there is
    // more", without folding the extra value.
    values.take(maxValues + 1).collect { tv ->
        if (total == maxValues) {
            truncated = true
            return@collect
        }
        total++
        val offset = tv.time.toEpochMilli() - fromMillis
        val index = if (offset < 0) 0L else offset / widthMillis
        byIndex.getOrPut(index) { Accumulator() }.add(tv.value)
    }
    val buckets = byIndex.map { (index, acc) ->
        acc.toBucket(Instant.ofEpochMilli(fromMillis + index * widthMillis))
    }
    return BucketedValues(buckets, total, truncated)
}

/**
 * Bucket width that divides `[from, to)` into at most [buckets] windows, so the
 * answer spans the whole range at the resolution the caller's cap implies.
 * Never shorter than a millisecond.
 */
fun spanningBucketWidth(from: Instant, to: Instant, buckets: Int): Duration {
    require(buckets > 0) { "buckets must be positive" }
    val span = (to.toEpochMilli() - from.toEpochMilli()).coerceAtLeast(1)
    return Duration.ofMillis((span + buckets - 1) / buckets)
}

/** Running count/min/max/sum for one bucket, so no per-bucket value list is kept. */
private class Accumulator {
    private var count = 0
    private var min = Double.MAX_VALUE
    private var max = -Double.MAX_VALUE
    private var sum = 0.0

    fun add(value: Double) {
        count++
        if (value < min) min = value
        if (value > max) max = value
        sum += value
    }

    fun toBucket(start: Instant): Bucket =
        Bucket(start = start, count = count, min = min, max = max, avg = sum / count)
}

/**
 * Evenly downsample [items] to at most [cap] entries, preserving order and always
 * retaining the first and last element. Used to spread the response across the
 * whole time range for record types that cannot be bucketed (sessions,
 * multi-value, cumulative) rather than returning its earliest slice.
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
