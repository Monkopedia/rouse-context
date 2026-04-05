package com.rousecontext.tunnel

/**
 * Errors that can occur during tunnel operation.
 */
sealed class TunnelError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class ConnectionFailed(message: String, cause: Throwable? = null) :
        TunnelError(message, cause)

    class WebSocketClosed(message: String) : TunnelError(message)
    class TlsHandshakeFailed(message: String, cause: Throwable? = null) :
        TunnelError(message, cause)

    class ProtocolError(message: String) : TunnelError(message)
}
