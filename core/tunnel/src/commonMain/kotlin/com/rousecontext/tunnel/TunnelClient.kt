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
     * Send the device's FCM token to the relay so it can send push wakeups.
     *
     * Must be called after [connect] succeeds (i.e. when [state] is [TunnelState.CONNECTED]).
     * Safe to call multiple times (e.g. when the FCM token refreshes).
     */
    suspend fun sendFcmToken(token: String)

    /**
     * Flow of incoming mux sessions.
     *
     * Each emission is a [MuxStream] representing a new session opened
     * by a remote MCP client via the relay.
     */
    val incomingSessions: Flow<MuxStream>
}
