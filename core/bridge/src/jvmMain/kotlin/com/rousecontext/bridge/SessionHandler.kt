package com.rousecontext.bridge

import com.rousecontext.mcp.core.INTERNAL_TOKEN_HEADER
import com.rousecontext.tunnel.MuxStream
import com.rousecontext.tunnel.TlsAcceptor
import com.rousecontext.tunnel.TunnelError
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import java.io.ByteArrayOutputStream
import javax.net.ssl.SSLContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handles an incoming [MuxStream] by performing TLS server-side accept,
 * then bridging the plaintext HTTP traffic to a local MCP session.
 *
 * This is the single production bridge implementation. Each call to
 * [handleStream] processes one MCP client session end-to-end:
 *
 * 1. TLS accept over the mux stream (device is the TLS server)
 * 2. Start a local MCP HTTP session
 * 3. Bridge plaintext bytes between the TLS stream and the MCP session
 * 4. Clean up on disconnect, or after [idleTimeout] with no bytes in either
 *    direction -- whichever ends first, the rest is cancelled and the local
 *    TCP socket closed.
 */
class SessionHandler(
    private val certProvider: TlsCertProvider,
    private val mcpSessionFactory: McpSessionFactory,
    /**
     * Ends a session that has moved no bytes in either direction for this long.
     * A parameter so tests can use a short bound; production uses the default.
     */
    private val idleTimeout: Duration = DEFAULT_IDLE_TIMEOUT,
    /**
     * How an incoming [MuxStream] becomes a TLS session. Defaults to the real
     * [TlsAcceptor]; exists as a parameter so tests can drive the byte-copy
     * loops with a stub [TlsAcceptor.TlsSession] instead of standing up a real
     * handshake. Production wiring never passes it.
     */
    private val tlsAccept: suspend (SSLContext, MuxStream) -> TlsAcceptor.TlsSession =
        { sslContext, stream -> TlsAcceptor.create(sslContext).accept(stream) }
) {

    /**
     * Selector for the loopback sockets. Lives as long as this handler -- a
     * process-lifetime singleton in production, like the Ktor engine it
     * connects to.
     */
    private val selectorManager = SelectorManager(Dispatchers.IO)

    /**
     * Handles a single incoming mux stream from the relay.
     *
     * Suspends until the session ends (remote disconnect, error, or cancellation).
     * Cleans up TLS, TCP, and MCP resources on exit.
     *
     * @throws IllegalStateException if no TLS certificate is available
     * @throws TunnelError.UnhandledTlsState if the TLS layer hits a state it has
     *   no handling for. Ordinary peer disconnects do NOT surface here -- they
     *   end the session quietly, which is what makes this throw worth reading.
     *   See #616.
     */
    suspend fun handleStream(stream: MuxStream) {
        val sslContext = certProvider.serverSslContext()
            ?: error("No TLS certificate available for server accept")

        val tlsSession = tlsAccept(sslContext, stream)

        val mcpHandle = mcpSessionFactory.create()
        try {
            bridgeToMcpServer(
                tlsSession = tlsSession,
                mcpPort = mcpHandle.port,
                internalToken = mcpHandle.internalToken
            )
        } finally {
            // Stop the MCP HTTP server even on cancellation so its worker threads
            // do not linger. Runs under NonCancellable so that the shutdown itself
            // is not short-circuited by the surrounding cancellation.
            withContext(NonCancellable) {
                mcpHandle.stop()
            }
        }
    }

    /**
     * Bridges a suspend-native [TlsAcceptor.TlsSession] to a local MCP server via TCP.
     *
     * Both directions are suspending -- the loopback socket is a Ktor
     * selector-backed socket, not a `java.net.Socket` -- so a session waiting
     * on a silent peer parks a coroutine, not a thread. With a blocking socket
     * read each live session held one [Dispatchers.IO] worker, and a burst of
     * clients that vanished mid-request (no more bytes, no CLOSE) filled the
     * 64-thread pool and wedged every `withContext(Dispatchers.IO)` in the app.
     * See #778.
     *
     * The session ends when either direction finishes, or when neither has
     * moved a byte for [idleTimeout]. The survivors are cancelled and the
     * socket is closed on the way out.
     */
    private suspend fun bridgeToMcpServer(
        tlsSession: TlsAcceptor.TlsSession,
        mcpPort: Int,
        internalToken: String
    ) = coroutineScope {
        aSocket(selectorManager).tcp().connect(LOOPBACK_HOST, mcpPort).use { socket ->
            val socketIn = socket.openReadChannel()
            val socketOut = socket.openWriteChannel()
            val injector = HttpHeaderInjector(
                "$INTERNAL_TOKEN_HEADER: $internalToken"
            )
            val activity = ActivityClock()
            val done = CompletableDeferred<Unit>()

            // TLS -> socket (client request bytes forwarded to MCP server).
            // The injector rewrites each request's header block to include
            // the shared secret that the local Ktor guard expects. See #177.
            val tlsToSocket = launch(Dispatchers.IO) {
                try {
                    copyTlsToChannel(tlsSession, socketOut, injector, activity)
                } finally {
                    done.complete(Unit)
                }
            }

            // Socket -> TLS (MCP server response bytes forwarded back through TLS).
            val socketToTls = launch(Dispatchers.IO) {
                try {
                    copyChannelToTls(socketIn, tlsSession, activity)
                } finally {
                    done.complete(Unit)
                }
            }

            // A peer that goes silent without closing never ends either copy
            // loop on its own, so bound the session by inactivity.
            val idleWatch = launch {
                activity.awaitIdle(idleTimeout)
                done.complete(Unit)
            }

            // Suspend until a direction finishes, the session idles out, or we
            // are cancelled.
            try {
                done.await()
            } finally {
                tlsToSocket.cancel()
                socketToTls.cancel()
                idleWatch.cancel()
            }
        }
    }

    /**
     * Copies plaintext bytes out of [tlsSession] and into [to] until EOF or error,
     * running every byte through [injector] so that each request's header block
     * is rewritten to include the `X-Internal-Token` shared secret.
     *
     * Ordinary failures -- the peer hanging up mid-copy, the stream closing --
     * are normal, frequent, and end the loop quietly. The exceptions that do
     * propagate are listed on [mustEndSession].
     */
    private suspend fun copyTlsToChannel(
        tlsSession: TlsAcceptor.TlsSession,
        to: ByteWriteChannel,
        injector: HttpHeaderInjector,
        activity: ActivityClock
    ) {
        try {
            val buf = ByteArray(BUFFER_SIZE)
            val injected = ByteArrayOutputStream(BUFFER_SIZE)
            while (true) {
                val n = tlsSession.read(buf)
                if (n == -1) break
                activity.touch()
                injected.reset()
                injector.feed(buf, 0, n) { out, off, len ->
                    injected.write(out, off, len)
                }
                to.writeFully(injected.toByteArray())
                to.flush()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (mustEndSession(e)) throw e
            // Stream closed or peer errored -- expected and frequent. Treat as
            // EOF and stay silent: making routine disconnects noisy would train
            // whoever reads the logs to ignore them, which is the same failure
            // one level up.
        }
    }

    /**
     * The two throwables the read direction must NOT file as an ordinary
     * disconnect. A predicate rather than two more `catch (e: X) { throw e }`
     * clauses only because three rethrows in one chain trips detekt's
     * `ThrowsCount`; the reasoning is per-type and is why it lives here rather
     * than inline.
     *
     *  - [TunnelError.UnhandledTlsState] is NOT a peer going away: our own TLS
     *    layer reached a state it has no handling for. Swallowing it reproduced
     *    the pre-#615 clean EOF bit for bit, so the guard #615 added could
     *    never be heard (#616). This module is a KMP jvm target with no Android
     *    logging or CrashReporter dependency, so propagating to
     *    `TunnelForegroundService.collectIncomingSessions` -- which catches per
     *    stream, so one bad session still cannot take the tunnel down -- is how
     *    the defect becomes observable.
     *
     *  - [HttpFramingLimitExceededException] means the peer sent a header block
     *    or chunk-size line larger than [HttpHeaderInjector] will buffer, so the
     *    injector aborted and is terminally failed. A session whose request
     *    direction has quietly stopped is worse than a closed one, so this ends
     *    the whole session. It is an [java.io.IOException] on purpose:
     *    `classifyTunnelFailure` files it as `PeerOrTransport` -- INFO, rate
     *    visible, no non-fatal -- because a remotely-triggerable crash report
     *    would be a spam channel.
     */
    private fun mustEndSession(e: Exception): Boolean =
        e is TunnelError.UnhandledTlsState || e is HttpFramingLimitExceededException

    /**
     * Copies bytes from [from] into [tlsSession] until EOF or error.
     *
     * Same quiet-on-disconnect, loud-on-defect split as [copyTlsToChannel]. See #616.
     */
    private suspend fun copyChannelToTls(
        from: ByteReadChannel,
        tlsSession: TlsAcceptor.TlsSession,
        activity: ActivityClock
    ) {
        try {
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = from.readAvailable(buf)
                if (n == -1) break
                activity.touch()
                tlsSession.write(buf, 0, n)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: TunnelError.UnhandledTlsState) {
            // Same discriminator as the read direction: a defect in our own TLS
            // layer is not a disconnect and must not be filed as one.
            //
            // #616 added this clause; until #630 it was inert, because
            // `SuspendTlsSession.write` reported BOTH a dead peer and an
            // unhandled wrap status as plain IOExceptions -- which is itself why
            // IOException cannot be the discriminator. `wrap` now classifies:
            // CLOSED stays an ordinary IOException and falls through to the
            // broad catch below, while BUFFER_OVERFLOW / BUFFER_UNDERFLOW arrive
            // here as UnhandledTlsState and propagate.
            throw e
        } catch (_: Exception) {
            // Stream closed or peer errored -- expected and frequent. Treat as
            // EOF and stay silent: making routine disconnects noisy would train
            // whoever reads the logs to ignore them, which is the same failure
            // one level up.
        }
    }

    companion object {
        private const val BUFFER_SIZE = 8192
        private const val LOOPBACK_HOST = "127.0.0.1"

        /**
         * How long a session may move no bytes in either direction before it
         * is torn down. Generous on purpose: a client waiting on a slow tool
         * call is silent too, and between requests Ktor already closes an idle
         * keep-alive connection after 45 s. This only has to catch a peer that
         * vanished mid-request, which nothing else ever ends. See #778.
         */
        val DEFAULT_IDLE_TIMEOUT: Duration = 5.minutes
    }
}

/** When either direction of a bridged session last moved a byte. */
private class ActivityClock {
    @Volatile
    private var lastNanos = System.nanoTime()

    fun touch() {
        lastNanos = System.nanoTime()
    }

    /** Suspends until nothing has called [touch] for [timeout]. */
    suspend fun awaitIdle(timeout: Duration) {
        while (true) {
            val idle = (System.nanoTime() - lastNanos).nanoseconds
            if (idle >= timeout) return
            delay(timeout - idle)
        }
    }
}
