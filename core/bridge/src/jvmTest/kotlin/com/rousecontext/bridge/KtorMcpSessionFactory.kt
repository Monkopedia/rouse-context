package com.rousecontext.bridge

import com.rousecontext.mcp.core.DeviceCodeManager
import com.rousecontext.mcp.core.InMemoryProviderRegistry
import com.rousecontext.mcp.core.InMemoryTokenStore
import com.rousecontext.mcp.core.configureMcpRouting
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

/**
 * Test implementation of [McpSessionFactory] that starts a Ktor embedded server
 * with MCP routing on an ephemeral port.
 *
 * Registers routes for every enabled integration in the registry.
 */
class KtorMcpSessionFactory(
    private val registry: InMemoryProviderRegistry,
    private val tokenStore: InMemoryTokenStore,
    private val deviceCodeManager: DeviceCodeManager =
        DeviceCodeManager(tokenStore = tokenStore)
) : McpSessionFactory {

    override suspend fun create(): McpSessionHandle {
        val defaultIntegration = registry.enabledPaths().firstOrNull() ?: "unknown"
        val server = embeddedServer(CIO, port = 0) {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                hostname = "test.rousecontext.com",
                integration = defaultIntegration
            )
        }
        server.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        return McpSessionHandle(
            port = port,
            stop = { server.stop() }
        )
    }
}
