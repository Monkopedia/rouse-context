package com.rousecontext.notifications.audit

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a single MCP tool call audit record.
 */
@Entity(tableName = "audit_entries")
data class AuditEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: String,
    val toolName: String,
    val provider: String,
    val timestampMillis: Long,
    val durationMillis: Long,
    val success: Boolean,
    val errorMessage: String? = null
)
