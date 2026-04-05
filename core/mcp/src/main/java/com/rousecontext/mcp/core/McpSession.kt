package com.rousecontext.mcp.core

import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

/**
 * Orchestrates a single MCP session over a raw byte stream.
 *
 * Creates an SDK [Server], lets each [McpServerProvider] register its tools/resources,
 * then connects via [StdioServerTransport] over the provided streams. Suspends until
 * the session ends (stream close, error, or [close] called).
 *
 * Usage:
 * ```
 * val session = McpSession(
 *     providers = listOf(healthServer),
 *     serverName = "rouse-context",
 *     serverVersion = "0.1.0",
 * )
 * // In a coroutine — suspends until session ends
 * session.run(tunnelInput, tunnelOutput)
 * ```
 */
class McpSession(
    private val providers: List<McpServerProvider>,
    @Suppress("UnusedPrivateProperty") // wired in follow-up PR
    private val auditListener: AuditListener? = null,
    private val serverName: String = "rouse-context",
    private val serverVersion: String = "0.1.0"
) {

    private var transport: StdioServerTransport? = null
    private val done = CompletableDeferred<Unit>()

    /**
     * Runs the MCP session. Suspends until the transport closes or [close] is called.
     */
    suspend fun run(input: InputStream, output: OutputStream) {
        val server = Server(
            Implementation(name = serverName, version = serverVersion),
            ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                    resources = ServerCapabilities.Resources(
                        subscribe = false,
                        listChanged = false
                    )
                )
            )
        )

        for (provider in providers) {
            provider.register(server)
        }

        val stdioTransport = StdioServerTransport(
            inputStream = input.asSource().buffered(),
            outputStream = output.asSink().buffered()
        )
        transport = stdioTransport

        // Register close callback before connecting so we don't miss it
        server.onClose {
            done.complete(Unit)
        }

        server.connect(stdioTransport)

        // Suspend until the server/transport closes
        done.await()
    }

    /**
     * Signals the session to stop. Safe to call from any coroutine.
     */
    suspend fun close() {
        transport?.close()
        done.complete(Unit)
    }
}
