package com.rousecontext.mcp.core

/**
 * Describes an MCP tool that a server exposes.
 *
 * @param name Unique tool identifier (e.g. "get_steps").
 * @param description Human-readable description of what the tool does.
 * @param parameters Map of parameter name to description. All parameters are strings.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, String> = emptyMap(),
)
