package com.rousecontext.work

import com.rousecontext.tunnel.MuxStream

/**
 * Handles incoming mux sessions from the relay.
 *
 * Called by [TunnelForegroundService] for each OPEN frame (new MCP client connection).
 * The implementation is responsible for TLS accept and MCP session setup.
 *
 * Implementations live in `:app` where both tunnel and MCP dependencies are available.
 */
interface SessionHandler {

    /**
     * Handle a new incoming mux stream.
     *
     * This suspends for the lifetime of the session (TLS handshake + HTTP request/response
     * exchange). The caller launches each invocation in a separate coroutine.
     *
     * @param stream The raw mux stream from the relay (carries TLS-encrypted bytes)
     * @throws Exception if TLS handshake or session handling fails
     */
    suspend fun handle(stream: MuxStream)
}
