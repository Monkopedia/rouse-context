package com.rousecontext.bridge

import java.io.IOException

/**
 * Raised by [HttpHeaderInjector] when the peer's request exceeds the HTTP/1.1
 * framing limits this layer is willing to buffer.
 *
 * The injector sits on the first plaintext byte after the TLS handshake --
 * ahead of HTTP parsing, ahead of the `X-Internal-Token` guard, ahead of
 * OAuth -- and it holds a request's whole header block in memory until the
 * `\r\n\r\n` terminator arrives. Nothing reaches the local Ktor server until
 * then, so Ktor's own limits cannot fire on a peer that simply never sends the
 * terminator: Ktor receives zero bytes while the buffer grows. The limits in
 * [HttpHeaderInjector] exist to bound that; this exception is how they are
 * reported.
 *
 * Deliberately an [IOException], because
 * `TunnelFailureReporting.classifyTunnelFailure` files an [IOException] as
 * `PeerOrTransport`: a peer sending malformed framing is something the peer
 * did, not a defect in this layer. It is logged at INFO so a *rate* stays
 * visible -- a sudden rise is what this being abused looks like -- without
 * spending a non-fatal crash report per attempt, which would hand a remote
 * caller a crash-report spam channel.
 */
internal class HttpFramingLimitExceededException(message: String) : IOException(message)
