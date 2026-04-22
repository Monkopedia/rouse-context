package com.rousecontext.notifications.audit

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a single MCP tool call audit record.
 *
 * @property clientLabel Human-readable label for the AI client that invoked
 *   the tool, captured once per MCP session from the OAuth Bearer token
 *   (issue #344). Nullable so rows created before the v3 -> v4 migration
 *   survive unchanged.
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
    val errorMessage: String? = null,
    val argumentsJson: String? = null,
    val resultJson: String? = null,
    val clientLabel: String? = null
)
