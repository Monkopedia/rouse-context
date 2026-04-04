package com.rousecontext.mcp.core

import io.modelcontextprotocol.kotlin.sdk.server.Server

/**
 * Contract that MCP server implementations (built-in or third-party) implement
 * to contribute tools and resources to an MCP session.
 *
 * Providers register their capabilities directly on the SDK [Server] instance
 * via [register]. In-process for now; a future bound-service IPC layer would
 * wrap this interface with a Binder proxy.
 */
interface McpServerProvider {

    /** Unique identifier for this provider, e.g. "health-connect". */
    val id: String

    /** Human-readable name shown in UI, e.g. "Health Connect". */
    val displayName: String

    /**
     * Register this provider's tools and resources on the given [server].
     *
     * Implementations should call [Server.addTool] and [Server.addResource]
     * using SDK types directly.
     */
    fun register(server: Server)
}
