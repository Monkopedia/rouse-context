package com.rousecontext.tunnel

/**
 * Errors that can occur during tunnel operation.
 */
sealed class TunnelError(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

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
    class StreamRefused(
        val streamId: UInt,
        message: String
    ) : TunnelError(message)

    /** A mux stream was reset by the remote. */
    class StreamReset(
        val streamId: UInt,
        message: String
    ) : TunnelError(message)

    /** Internal error on the remote side. */
    class InternalError(message: String) : TunnelError(message)

    /** Certificate store operation failed. */
    class CertificateError(message: String) : TunnelError(message)

    /** Invalid state transition attempted. */
    class InvalidStateTransition(
        val from: TunnelState,
        val to: TunnelState
    ) : TunnelError("Invalid transition from $from to $to")
}
