package com.rousecontext.mcp.core

/**
 * Callback interface for logging MCP tool calls.
 * Implementations persist or display audit records.
 */
interface AuditListener {
    suspend fun onToolCall(event: ToolCallEvent)
}
