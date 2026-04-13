package com.rousecontext.notifications.audit

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for audit entries.
 */
@Dao
interface AuditDao {

    @Insert
    suspend fun insert(entry: AuditEntry): Long

    @Query("SELECT * FROM audit_entries WHERE id = :id")
    suspend fun getById(id: Long): AuditEntry?

    @Query("SELECT * FROM audit_entries WHERE sessionId = :sessionId ORDER BY timestampMillis ASC")
    suspend fun queryBySession(sessionId: String): List<AuditEntry>

    @Query(
        "SELECT * FROM audit_entries " +
            "WHERE timestampMillis >= :startMillis AND timestampMillis <= :endMillis " +
            "AND (:provider IS NULL OR provider = :provider) " +
            "ORDER BY timestampMillis DESC"
    )
    suspend fun queryByDateRange(
        startMillis: Long,
        endMillis: Long,
        provider: String? = null
    ): List<AuditEntry>

    /**
     * Reactive version - Room re-emits whenever the audit_entries table changes.
     * Returns the most recent [limit] entries within the given time window.
     */
    @Query(
        "SELECT * FROM audit_entries " +
            "WHERE timestampMillis >= :startMillis AND timestampMillis <= :endMillis " +
            "ORDER BY timestampMillis DESC " +
            "LIMIT :limit"
    )
    fun observeRecent(startMillis: Long, endMillis: Long, limit: Int): Flow<List<AuditEntry>>

    /**
     * Reactive version of [queryByDateRange] - Room re-emits whenever the table changes.
     */
    @Query(
        "SELECT * FROM audit_entries " +
            "WHERE timestampMillis >= :startMillis AND timestampMillis <= :endMillis " +
            "AND (:provider IS NULL OR provider = :provider) " +
            "ORDER BY timestampMillis DESC"
    )
    fun observeByDateRange(
        startMillis: Long,
        endMillis: Long,
        provider: String? = null
    ): Flow<List<AuditEntry>>

    @Query("DELETE FROM audit_entries WHERE timestampMillis < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long): Int

    @Query("SELECT COUNT(*) FROM audit_entries")
    suspend fun count(): Int

    /**
     * Returns the largest id currently in the table, or null if empty.
     * Used as a cursor to detect entries inserted after a point in time
     * (e.g. between the start and end of an MCP session).
     */
    @Query("SELECT MAX(id) FROM audit_entries")
    suspend fun latestId(): Long?

    /**
     * Returns all entries created after the given id, ordered by id ascending.
     * Used to materialize entries added during a tunnel session.
     */
    @Query("SELECT * FROM audit_entries WHERE id > :sinceId ORDER BY id ASC")
    suspend fun queryCreatedAfter(sinceId: Long): List<AuditEntry>
}
