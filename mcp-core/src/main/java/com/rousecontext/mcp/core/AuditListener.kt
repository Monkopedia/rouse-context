package com.rousecontext.mcp.core

import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import kotlinx.serialization.json.JsonElement

/**
 * Callback for auditing MCP tool invocations.
 * Implemented by :app to persist a per-tool-call history log.
 */
fun interface AuditListener {
    fun onToolCall(event: ToolCallEvent)
}

data class ToolCallEvent(
    val timestamp: Long,
    val toolName: String,
    val arguments: Map<String, JsonElement>,
    val result: CallToolResult,
    val durationMs: Long
)
