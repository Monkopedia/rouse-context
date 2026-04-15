package com.rousecontext.integrations.health.query

import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import java.time.Instant
import kotlin.reflect.KClass

/**
 * In-memory fake [RecordReader] for category unit tests.
 *
 * Populate [records] via [put] with stub records keyed by their [KClass].
 * [read] filters to the requested time range so tests can verify that
 * categories pass [from]/[to] through unchanged.
 */
class FakeRecordReader : RecordReader {

    /** Records by type. */
    private val records: MutableMap<KClass<out Record>, List<Record>> = mutableMapOf()

    /** Captured read requests for assertions. */
    val reads: MutableList<ReadCall> = mutableListOf()

    fun <T : Record> put(type: KClass<T>, value: List<T>) {
        records[type] = value
    }

    data class ReadCall(val type: KClass<out Record>, val from: Instant, val to: Instant)

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Record> read(type: KClass<T>, from: Instant, to: Instant): List<T> {
        reads += ReadCall(type, from, to)
        return (records[type] as? List<T>) ?: emptyList()
    }
}

internal val testMetadata: Metadata by lazy {
    Metadata.manualEntry(Device(type = Device.TYPE_PHONE))
}
