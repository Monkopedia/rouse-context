package com.rousecontext.tunnel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Client interface for the relay tunnel.
 *
 * Manages the connection to the relay server and exposes incoming
 * mux sessions as a flow of [MuxStream].
 */
interface TunnelClient {
    /** Current connection state. */
    val state: StateFlow<TunnelState>

    /** Error events (non-fatal errors that don't necessarily disconnect). */
    val errors: SharedFlow<TunnelError>

    /** Connect to the relay server at the given URL. */
    suspend fun connect(url: String)

    /** Gracefully disconnect. */
    suspend fun disconnect()

    /**
     * Flow of incoming mux sessions.
     *
     * Each emission is a [MuxStream] representing a new session opened
     * by a remote MCP client via the relay.
     */
    val incomingSessions: Flow<MuxStream>
}
