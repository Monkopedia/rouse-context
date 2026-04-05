package com.rousecontext.mcp.core

/**
 * Record of a single MCP tool invocation.
 */
data class ToolCallEvent(
    val sessionId: String,
    val toolName: String,
    val provider: String,
    val timestampMillis: Long,
    val durationMillis: Long,
    val success: Boolean,
    val errorMessage: String? = null,
)
