package com.rousecontext.mcp.health.query

import androidx.health.connect.client.records.Record
import java.time.Instant
import kotlin.reflect.KClass

/**
 * Seam for reading Health Connect records.
 *
 * Abstracts the Health Connect SDK call so per-category query classes can be
 * tested with a fake reader that returns canned records.
 */
interface RecordReader {
    suspend fun <T : Record> read(type: KClass<T>, from: Instant, to: Instant): List<T>
}
