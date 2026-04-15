package com.rousecontext.notifications.audit

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a single MCP JSON-RPC request audit record.
 *
 * Captures every method (not just `tools/call`) so users can see the full
 * set of requests a connected client has made (initialize, tools/list,
 * resources/read, etc.). Surfaced in the audit UI only when the user opts
 * in via the "Show all MCP messages" setting.
 *
 * `paramsJson` may contain user-data-relevant fields for methods such as
 * `prompts/get` and `resources/read`, so it is encrypted at rest via the
 * same [com.rousecontext.notifications.FieldEncryptor] used for
 * tool-call arguments.
 */
@Entity(tableName = "mcp_request_entries")
data class McpRequestEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: String,
    val provider: String,
    val method: String,
    val timestampMillis: Long,
    val durationMillis: Long,
    val resultBytes: Int? = null,
    /** JSON-encoded raw params; encrypted at rest when a field encryptor is configured. */
    val paramsJson: String? = null
)
