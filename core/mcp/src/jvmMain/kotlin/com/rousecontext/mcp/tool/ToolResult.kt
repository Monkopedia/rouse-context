package com.rousecontext.mcp.tool

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Result of a tool execution. Converted to [CallToolResult] by the framework.
 *
 *  - [Success] — plain-text success payload.
 *  - [Error]   — plain-text error payload; the framework maps to `isError = true`.
 *  - [Json]    — raw JSON payload delivered as a single [TextContent]. Used when a
 *                tool wants to return a structured result without handcrafting the
 *                JSON-in-string encoding itself.
 */
sealed class ToolResult {
    data class Success(val text: String, val meta: JsonObject? = null) : ToolResult()

    data class Error(val message: String, val meta: JsonObject? = null) : ToolResult()

    data class Json(val value: JsonElement, val meta: JsonObject? = null) : ToolResult()

    /**
     * Convert to the MCP SDK's [CallToolResult]. Keeps wire-format stable across
     * tool-author code churn.
     */
    fun toCallToolResult(): CallToolResult = when (this) {
        is Success -> CallToolResult(
            content = listOf(TextContent(text)),
            isError = false
        )
        is Error -> CallToolResult(
            content = listOf(TextContent(message)),
            isError = true
        )
        is Json -> CallToolResult(
            content = listOf(TextContent(value.toString())),
            isError = false
        )
    }
}
