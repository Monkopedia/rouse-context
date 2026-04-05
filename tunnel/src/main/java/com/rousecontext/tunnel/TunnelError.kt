package com.rousecontext.tunnel

/**
 * Errors that can occur during tunnel operation.
 */
sealed interface TunnelError {
    val message: String

    /** Connection-level error (relay unreachable, TLS handshake failed, etc.) */
    data class ConnectionError(override val message: String) : TunnelError

    /** Stream-level error (single MCP session failed within a mux connection) */
    data class StreamError(
        override val message: String,
        val streamId: Int,
    ) : TunnelError
}
