package com.rousecontext.mcp.core

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CompletableDeferred

/**
 * Orchestrates MCP HTTP sessions with per-integration OAuth and auth.
 *
 * Wraps a Ktor embedded HTTP server that routes by integration path prefix.
 * Each integration path is an independent MCP server with its own OAuth endpoints,
 * device codes, and tokens.
 *
 * The session is long-lived and shared across streams. Provider changes
 * (enable/disable) are reflected immediately via [ProviderRegistry].
 *
 * Usage:
 * ```
 * val session = McpSession(
 *     registry = providerRegistry,
 *     tokenStore = roomTokenStore,
 *     auditListener = auditLog,
 *     hostname = "brave-falcon.rousecontext.com"
 * )
 * session.start(port = 0) // starts embedded HTTP server
 * session.awaitClose()    // suspends until stopped
 * ```
 */
class McpSession(
    private val registry: ProviderRegistry,
    private val tokenStore: TokenStore,
    private val deviceCodeManager: DeviceCodeManager = DeviceCodeManager(tokenStore = tokenStore),
    private val auditListener: AuditListener? = null,
    private val hostname: String = "localhost",
    private val serverName: String = "rouse-context",
    private val serverVersion: String = "0.1.0"
) {

    private val done = CompletableDeferred<Unit>()
    private var engine: io.ktor.server.engine.EmbeddedServer<*, *>? = null

    /**
     * Starts the embedded HTTP server on the given port.
     * Use port 0 for an OS-assigned ephemeral port.
     */
    fun start(port: Int = 0) {
        val server = embeddedServer(CIO, port = port) {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                hostname = hostname,
                auditListener = auditListener,
                serverName = serverName,
                serverVersion = serverVersion
            )
        }
        engine = server
        server.start(wait = false)
    }

    /**
     * Suspends until [stop] is called.
     */
    suspend fun awaitClose() {
        done.await()
    }

    /**
     * Stops the HTTP server and completes the session.
     */
    fun stop() {
        engine?.stop()
        done.complete(Unit)
    }
}
