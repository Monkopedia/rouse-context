package com.rousecontext.work

import android.util.Log
import com.rousecontext.api.CrashReporter
import com.rousecontext.tunnel.TunnelError
import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * What a throwable escaping a tunnel boundary actually *is*.
 *
 * Three values, not two. The service boundary sits above a TLS layer that spent
 * six PRs (#616, #626, #630, #639, #643, #647) learning to tell its own defects
 * apart from ordinary peer behaviour; collapsing that back to a binary at the
 * boundary is #642 in either direction — report everything and the crash
 * channel becomes noise nobody reads, report nothing and six PRs of visibility
 * are silently undone.
 */
internal enum class TunnelFailureKind {

    /**
     * The scope is shutting down (service destroyed, idle timeout, tunnel
     * teardown). Not a failure at all: never logged as an error, never
     * reported, and it MUST propagate so the coroutine completes as cancelled.
     */
    Cancellation,

    /**
     * The peer, the network, or the relay did something ordinary — hung up,
     * aborted mid-handshake, reset a stream. Routine and frequent on this
     * transport; recorded at INFO so a *rate* is still visible in logcat, but
     * never filed as a crash.
     */
    PeerOrTransport,

    /**
     * This layer reached a state it has no handling for. This is what the
     * crash channel is for and it must stay loud.
     */
    Defect
}

/**
 * Classifies a throwable escaping `handleStream` / `connect` at the
 * [TunnelForegroundService] boundary.
 *
 * Uses the vocabulary the TLS layer already established rather than inventing a
 * parallel scheme:
 *
 *  - [TunnelError.UnhandledTlsState] is, by its own kdoc, "a defect in our own
 *    TLS/mux code, not anything the peer did". Loud.
 *  - [TunnelError.TlsHandshakeFailed] is the handshake not completing — which
 *    on this transport overwhelmingly means the client walked away mid-TLS
 *    (#618). Quiet.
 *  - A plain [IOException] is what an ordinary disconnect looks like all the
 *    way down (`SuspendTlsSession.write` throws exactly that for a dead peer),
 *    which is precisely why `UnhandledTlsState` had to be its own type. Quiet.
 *
 * Note [TunnelError] extends [Exception], **not** [IOException], so the
 * `is IOException` arm cannot accidentally capture a tunnel error.
 *
 * The quiet set is a closed **allowlist** and the `else` arm is [Defect]. That
 * direction matters: an exception nobody anticipated here — an
 * [IllegalStateException] from the missing-cert `error(...)` in
 * `SessionHandler.handleStream`, an NPE, a `TunnelError.ProtocolError` — is by
 * definition something this layer did not plan for, so the default has to be
 * "loud" or the fix decays into "report nothing".
 *
 * `TunnelError.ConnectionFailed`, `WebSocketClosed`, `StreamRefused` and
 * `StreamReset` are named explicitly because they describe things the far end
 * did; `ProtocolError`, `InternalError`, `CertificateError` and
 * `InvalidStateTransition` are deliberately left to the `else` arm because a
 * mangled frame, a relay-side internal error, a broken cert store or an illegal
 * state machine move are all somebody's defect, not routine traffic.
 */
internal fun classifyTunnelFailure(e: Throwable): TunnelFailureKind = when (e) {
    // Must come first: CancellationException is an Exception (it extends
    // IllegalStateException), so any later arm could otherwise swallow it.
    is CancellationException -> TunnelFailureKind.Cancellation
    is TunnelError.UnhandledTlsState -> TunnelFailureKind.Defect
    is TunnelError.TlsHandshakeFailed,
    is TunnelError.ConnectionFailed,
    is TunnelError.WebSocketClosed,
    is TunnelError.StreamRefused,
    is TunnelError.StreamReset -> TunnelFailureKind.PeerOrTransport
    is IOException -> TunnelFailureKind.PeerOrTransport
    else -> TunnelFailureKind.Defect
}

/**
 * The single boundary policy, applied identically wherever the service wraps a
 * tunnel call in a broad catch.
 *
 * @throws CancellationException always, when [e] is one — cancellation is
 *   re-thrown rather than merely un-reported, per `.claude/rules/coroutines.md`.
 *   Callers need no `NonCancellable` wrapper for this: the only cleanup at
 *   these sites is a non-suspending [Log] call. The suspending cleanup that
 *   *does* need it (`mcpHandle.stop()`) already runs under `NonCancellable`
 *   inside `SessionHandler.handleStream`'s own `finally`.
 */
internal fun CrashReporter.reportTunnelFailure(tag: String, message: String, e: Throwable) {
    when (classifyTunnelFailure(e)) {
        TunnelFailureKind.Cancellation -> throw e
        TunnelFailureKind.PeerOrTransport ->
            // INFO, not WARN and not silence. The issue asks for the abort
            // *rate* to stay audible (a sudden rise is what a broken cert or a
            // mangled relay splice looks like) without spending a non-fatal per
            // occurrence. Counting-and-sampling (#642 option 2) is deliberately
            // not built yet: nobody has measured the rate, and this line is what
            // makes measuring it possible.
            Log.i(tag, "$message: ${e.javaClass.simpleName}: ${e.message}")
        TunnelFailureKind.Defect -> {
            Log.e(tag, message, e)
            logCaughtException(e)
        }
    }
}
