package com.rousecontext.tunnel

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
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
}
