package com.rousecontext.tunnel

import kotlin.time.Duration
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
     * Actively probe whether the tunnel is alive.
     *
     * Sends an application-layer Ping on the mux channel and waits up to
     * [timeout] for the matching Pong. Returns true iff a Pong arrived before
     * the deadline.
     *
     * Callers should prefer this over reading [state] when they need a
     * ground-truth check (e.g. on FCM wake, before deciding to skip reconnect).
     * A half-open TCP socket will happily sit in [TunnelState.ACTIVE] while
     * silently eating packets; a Ping with a bounded deadline is the only
     * reliable way to catch that.
     *
     * Returns false if the tunnel is not currently connected, if the write
     * fails, or if no Pong arrives within [timeout]. After a false result the
     * caller should tear down and reconnect the tunnel.
     */
    suspend fun healthCheck(timeout: Duration): Boolean

    /**
     * Flow of incoming mux sessions.
     *
     * Each emission is a [MuxStream] representing a new session opened
     * by a remote MCP client via the relay.
     */
    val incomingSessions: Flow<MuxStream>
}
