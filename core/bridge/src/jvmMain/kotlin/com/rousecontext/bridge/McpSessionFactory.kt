package com.rousecontext.bridge

/**
 * Creates and manages MCP HTTP sessions for bridging TLS plaintext streams.
 *
 * Production implementations start a Ktor embedded server with [McpSession] routing.
 * Test implementations can use simple echo servers or test providers.
 */
interface McpSessionFactory {

    /**
     * Starts an MCP session and returns the local port it is listening on,
     * plus a cleanup function to stop the session.
     *
     * The bridge will forward plaintext HTTP traffic from the TLS stream
     * to this port via TCP.
     */
    suspend fun create(): McpSessionHandle
}

/**
 * Handle to a running MCP session, providing the local port for TCP bridging
 * and a way to stop the session.
 */
data class McpSessionHandle(
    /** Local port the MCP HTTP server is listening on. */
    val port: Int,
    /** Stops the MCP HTTP server and cleans up resources. */
    val stop: () -> Unit
)
