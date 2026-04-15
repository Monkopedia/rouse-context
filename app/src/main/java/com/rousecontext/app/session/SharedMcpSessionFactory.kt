package com.rousecontext.app.session

import com.rousecontext.bridge.McpSessionFactory
import com.rousecontext.bridge.McpSessionHandle
import com.rousecontext.mcp.core.McpSession

/**
 * Production [McpSessionFactory] that returns the single long-lived [McpSession]
 * started at app boot.
 *
 * The bridge interface models factories that own the session lifecycle (the test
 * factory creates an ephemeral Ktor server per stream and stops it on cleanup).
 * In production the Ktor server is a singleton shared across all streams, so
 * [McpSessionHandle.stop] is a no-op — stopping the shared server per-stream
 * would tear down every other in-flight connection.
 */
class SharedMcpSessionFactory(private val session: McpSession) : McpSessionFactory {

    override suspend fun create(): McpSessionHandle {
        val port = session.resolvePort()
        check(port > 0) { "McpSession not started; port unresolved" }
        return McpSessionHandle(
            port = port,
            stop = { /* shared session survives individual streams */ }
        )
    }
}
