package com.rousecontext.notifications.audit

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for generic MCP request audit entries.
 *
 * These entries cover every JSON-RPC method (initialize, tools/list,
 * resources/read, ping, notifications/initialized, etc.). Tool calls
 * continue to be persisted in [AuditEntry] via [AuditDao] for the
 * primary audit UI.
 */
@Dao
interface McpRequestDao {

    @Insert
    suspend fun insert(entry: McpRequestEntry): Long

    @Query("SELECT * FROM mcp_request_entries WHERE id = :id")
    suspend fun getById(id: Long): McpRequestEntry?

    @Query(
        "SELECT * FROM mcp_request_entries " +
            "WHERE timestampMillis >= :startMillis AND timestampMillis <= :endMillis " +
            "AND (:provider IS NULL OR provider = :provider) " +
            "ORDER BY timestampMillis DESC"
    )
    suspend fun queryByDateRange(
        startMillis: Long,
        endMillis: Long,
        provider: String? = null
    ): List<McpRequestEntry>

    @Query(
        "SELECT * FROM mcp_request_entries " +
            "WHERE timestampMillis >= :startMillis AND timestampMillis <= :endMillis " +
            "AND (:provider IS NULL OR provider = :provider) " +
            "ORDER BY timestampMillis DESC"
    )
    fun observeByDateRange(
        startMillis: Long,
        endMillis: Long,
        provider: String? = null
    ): Flow<List<McpRequestEntry>>

    @Query("DELETE FROM mcp_request_entries WHERE timestampMillis < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long): Int

    @Query("SELECT COUNT(*) FROM mcp_request_entries")
    suspend fun count(): Int
}
