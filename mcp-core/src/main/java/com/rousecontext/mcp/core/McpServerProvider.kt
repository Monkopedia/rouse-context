package com.rousecontext.mcp.core

/**
 * Contract that MCP server implementations fulfill.
 *
 * Each provider exposes a set of [ToolDefinition]s and handles tool calls
 * dispatched by name. Providers are registered with a session orchestrator
 * that bridges them to the MCP wire protocol.
 */
interface McpServerProvider {

    /** Unique identifier for this provider (e.g. "health_connect"). */
    val id: String

    /** Human-readable display name. */
    val displayName: String

    /** The tools this provider exposes. */
    fun tools(): List<ToolDefinition>

    /**
     * Execute a tool call.
     *
     * @param name The tool name, guaranteed to match one of [tools].
     * @param arguments Key-value arguments supplied by the caller.
     * @return The result of the tool call.
     */
    suspend fun callTool(name: String, arguments: Map<String, String>): ToolResult
}
