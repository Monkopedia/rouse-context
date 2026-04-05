package com.rousecontext.mcp.core

/**
 * Result of calling an MCP tool.
 */
sealed class ToolResult {
    /** Successful tool call with a text payload. */
    data class Success(val content: String) : ToolResult()

    /** Tool call failed with an error message. */
    data class Error(val message: String) : ToolResult()
}
