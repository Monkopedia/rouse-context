package com.rousecontext.tunnel

/** Errors that can occur during tunnel operation. */
sealed interface TunnelError {
    /** Human-readable description of the error. */
    val message: String

    /** TLS handshake failed (cert expired, untrusted, etc.). */
    data class TlsError(override val message: String) : TunnelError

    /** TCP connection to relay failed or was lost. */
    data class ConnectionFailed(override val message: String) : TunnelError

    /** Mux protocol violation (bad frame, unexpected type). */
    data class ProtocolError(override val message: String) : TunnelError

    /** A mux stream was refused by the remote. */
    data class StreamRefused(
        val streamId: UInt,
        override val message: String,
    ) : TunnelError

    /** A mux stream was reset by the remote. */
    data class StreamReset(
        val streamId: UInt,
        override val message: String,
    ) : TunnelError

    /** Internal error on the remote side. */
    data class InternalError(override val message: String) : TunnelError

    /** Certificate store operation failed. */
    data class CertificateError(override val message: String) : TunnelError

    /** Invalid state transition attempted. */
    data class InvalidStateTransition(
        val from: TunnelState,
        val to: TunnelState,
        override val message: String = "Invalid transition from $from to $to",
    ) : TunnelError
}
