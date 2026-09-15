package com.rousecontext.integrations.health.query

import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import java.time.Instant
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList

/**
 * In-memory fake [RecordReader] for category unit tests.
 *
 * Populate [records] via [put] with stub records keyed by their [KClass].
 * [read] honours the caller's cap and records the call, so tests can verify both
 * the time range and the bound that reached the read.
 */
class FakeRecordReader : RecordReader {

    /** Records by type. */
    private val records: MutableMap<KClass<out Record>, List<Record>> = mutableMapOf()

    /** Captured read requests for assertions. */
    val reads: MutableList<ReadCall> = mutableListOf()

    fun <T : Record> put(type: KClass<T>, value: List<T>) {
        records[type] = value
    }

    data class ReadCall(
        val type: KClass<out Record>,
        val from: Instant,
        val to: Instant,
        val maxRecords: Int
    )

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Record> read(
        type: KClass<T>,
        from: Instant,
        to: Instant,
        maxRecords: Int
    ): List<T> {
        reads += ReadCall(type, from, to, maxRecords)
        return ((records[type] as? List<T>) ?: emptyList()).take(maxRecords)
    }

    /**
     * When true, streams end with [Streamed.CeilingReached] — the fake standing
     * in for a reader that stopped at its record ceiling rather than at the end
     * of the range.
     */
    var streamHitsCeiling: Boolean = false

    @Suppress("UNCHECKED_CAST")
    override fun <T : Record> stream(
        type: KClass<T>,
        from: Instant,
        to: Instant
    ): Flow<Streamed<T>> {
        reads += ReadCall(type, from, to, Int.MAX_VALUE)
        val stored: List<Streamed<T>> =
            ((records[type] as? List<T>) ?: emptyList()).map { Streamed.Value(it) }
        return (if (streamHitsCeiling) stored + Streamed.CeilingReached else stored).asFlow()
    }
}

internal val testMetadata: Metadata by lazy {
    Metadata.manualEntry(Device(type = Device.TYPE_PHONE))
}

/**
 * Present plain values as a stream that ended because its source ran out — no
 * terminal note, so nothing cut it short.
 */
internal fun <T> Flow<T>.streamed(): Flow<Streamed<T>> = map { Streamed.Value(it) }

/** Collect a stream's payload values, dropping any terminal note. */
internal suspend fun <T> Flow<Streamed<T>>.valueList(): List<T> =
    filterIsInstance<Streamed.Value<T>>().map { it.value }.toList()
