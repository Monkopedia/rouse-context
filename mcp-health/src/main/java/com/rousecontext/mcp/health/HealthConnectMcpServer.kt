package com.rousecontext.mcp.health

import com.rousecontext.mcp.core.McpServerProvider
import io.modelcontextprotocol.kotlin.sdk.server.Server

/**
 * Health Connect MCP server implementation.
 * Exposes Health Connect data (steps, heart rate, sleep, etc.) as MCP tools/resources.
 */
class HealthConnectMcpServer : McpServerProvider {

    override val id = "health-connect"
    override val displayName = "Health Connect"

    override fun register(server: Server) {
        // TODO: register Health Connect tools and resources
    }
}
