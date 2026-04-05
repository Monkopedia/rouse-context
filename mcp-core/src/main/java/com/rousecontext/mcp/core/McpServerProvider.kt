package com.rousecontext.mcp.core

import io.modelcontextprotocol.kotlin.sdk.server.Server

/**
 * Contract that both built-in and third-party MCP servers implement.
 * Each provider registers its tools and resources on the shared [Server].
 */
interface McpServerProvider {
    /**
     * Register this provider's tools and resources on the given MCP [server].
     * Called once during session setup.
     */
    fun register(server: Server)
}
