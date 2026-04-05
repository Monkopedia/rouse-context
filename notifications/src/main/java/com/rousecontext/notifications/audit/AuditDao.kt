package com.rousecontext.notifications.audit

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Data access object for audit entries.
 */
@Dao
interface AuditDao {

    @Insert
    suspend fun insert(entry: AuditEntry): Long

    @Query("SELECT * FROM audit_entries WHERE sessionId = :sessionId ORDER BY timestampMillis ASC")
    suspend fun queryBySession(sessionId: String): List<AuditEntry>

    @Query(
        "SELECT * FROM audit_entries " +
            "WHERE timestampMillis >= :startMillis AND timestampMillis <= :endMillis " +
            "AND (:provider IS NULL OR provider = :provider) " +
            "ORDER BY timestampMillis ASC",
    )
    suspend fun queryByDateRange(
        startMillis: Long,
        endMillis: Long,
        provider: String? = null,
    ): List<AuditEntry>

    @Query("DELETE FROM audit_entries WHERE timestampMillis < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long): Int

    @Query("SELECT COUNT(*) FROM audit_entries")
    suspend fun count(): Int
}
