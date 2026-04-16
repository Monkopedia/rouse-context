package com.rousecontext.bridge

import com.rousecontext.mcp.core.DeviceCodeManager
import com.rousecontext.mcp.core.InMemoryProviderRegistry
import com.rousecontext.mcp.core.InMemoryTokenStore
import com.rousecontext.mcp.core.configureMcpRouting
import com.rousecontext.mcp.core.generateInternalToken
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
        // Generate a fresh internal token so the Ktor guard rejects any direct
        // loopback traffic that does not come through the bridge. The handle
        // exposes the same token so the bridge can inject it on every request.
        // See issue #177.
        val token = generateInternalToken()
        val server = embeddedServer(CIO, port = 0) {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                hostname = "test.rousecontext.com",
                integration = defaultIntegration,
                internalToken = token
            )
        }
        server.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        return McpSessionHandle(
            port = port,
            internalToken = token,
            stop = { server.stop() }
        )
    }
}
