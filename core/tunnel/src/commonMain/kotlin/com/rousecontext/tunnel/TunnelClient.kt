package com.rousecontext.tunnel

<<<<<<< HEAD
import kotlinx.coroutines.flow.Flow
=======
>>>>>>> feat/tunnel-websocket-tls
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
<<<<<<< HEAD
 * Client interface for the relay tunnel.
 *
 * Manages the connection to the relay server and exposes incoming
 * mux sessions as a flow of [MuxStream] pairs (input/output).
 */
interface TunnelClient {
    /** Current connection state. */
    val state: StateFlow<TunnelState>

    /** Error events (non-fatal errors that don't necessarily disconnect). */
    val errors: SharedFlow<TunnelError>

    /** Connect to the relay server. Transitions DISCONNECTED -> CONNECTING -> CONNECTED. */
    suspend fun connect()

    /** Gracefully disconnect. Transitions to DISCONNECTING -> DISCONNECTED. */
    suspend fun disconnect()

    /**
     * Flow of incoming mux sessions.
     *
     * Each emission is a [MuxStream] representing a new session opened
     * by a remote MCP client via the relay.
     */
    val incomingSessions: Flow<MuxStream>
=======
 * Client interface for establishing and managing a tunnel connection to the relay server.
 * The tunnel carries multiplexed streams over a single WebSocket connection.
 */
interface TunnelClient {
    /**
     * Current connection state.
     */
    val state: StateFlow<TunnelState>

    /**
     * Flow of incoming sessions (MuxStreams) opened by the relay.
     */
    val incomingSessions: SharedFlow<MuxStream>

    /**
     * Flow of errors encountered during tunnel operation.
     */
    val errors: SharedFlow<TunnelError>

    /**
     * Connect to the relay server at the given URL.
     */
    suspend fun connect(url: String)

    /**
     * Disconnect from the relay, closing all active streams.
     */
    suspend fun disconnect()
>>>>>>> feat/tunnel-websocket-tls
}
