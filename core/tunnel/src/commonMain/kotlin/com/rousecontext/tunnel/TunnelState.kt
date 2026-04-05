package com.rousecontext.tunnel

/**
 * Represents the lifecycle state of a tunnel connection.
 *
 * Valid transitions:
 * - DISCONNECTED -> CONNECTING
 * - CONNECTING -> CONNECTED
 * - CONNECTING -> DISCONNECTED (connection failed)
 * - CONNECTED -> ACTIVE (first mux stream opened)
 * - CONNECTED -> DISCONNECTING
 * - ACTIVE -> DISCONNECTING
 * - DISCONNECTING -> DISCONNECTED
 */
enum class TunnelState {
    /** No connection to the relay server. */
    DISCONNECTED,

    /** TCP connection initiated, TLS handshake in progress. */
    CONNECTING,

    /** TLS established, mux layer ready, no active streams. */
    CONNECTED,

    /** At least one mux stream is open and carrying data. */
    ACTIVE,

    /** Graceful shutdown in progress, draining streams. */
    DISCONNECTING,
}
