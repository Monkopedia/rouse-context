package com.rousecontext.tunnel

/**
 * Errors that can occur during tunnel operation.
 */
sealed class TunnelError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** TLS handshake failed (cert expired, untrusted, etc.). */
    class TlsHandshakeFailed(message: String, cause: Throwable? = null) :
        TunnelError(message, cause)

    /** TCP/WebSocket connection to relay failed or was lost. */
    class ConnectionFailed(message: String, cause: Throwable? = null) :
        TunnelError(message, cause)

    /** WebSocket closed by remote. */
    class WebSocketClosed(message: String) : TunnelError(message)

    /** Mux protocol violation (bad frame, unexpected type). */
    class ProtocolError(message: String) : TunnelError(message)

    /** A mux stream was refused by the remote. */
    class StreamRefused(val streamId: UInt, message: String) : TunnelError(message)

    /** A mux stream was reset by the remote. */
    class StreamReset(val streamId: UInt, message: String) : TunnelError(message)

    /** Internal error on the remote side. */
    class InternalError(message: String) : TunnelError(message)

    /** Certificate store operation failed. */
    class CertificateError(message: String) : TunnelError(message)

    /**
     * This layer reached a state it has no handling for -- a defect in our own
     * TLS/mux code, not anything the peer did.
     *
     * Deliberately its own type rather than a bare [java.io.IOException]: an
     * ordinary peer disconnect surfaces as an `IOException` too (see
     * `SuspendTlsSession.write`, which throws `IOException("TLS write failed:
     * stream closed")` for exactly that), so `IOException` cannot tell a defect
     * apart from a routine hang-up. The session copy loops in `SessionHandler`
     * key off THIS type to stay quiet about disconnects while still surfacing
     * a defect. See #565, #615, #616.
     */
    class UnhandledTlsState(message: String) : TunnelError(message)

    /** Invalid state transition attempted. */
    class InvalidStateTransition(val from: TunnelState, val to: TunnelState) :
        TunnelError("Invalid transition from $from to $to")
}
