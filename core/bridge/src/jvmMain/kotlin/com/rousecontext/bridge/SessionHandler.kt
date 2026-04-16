package com.rousecontext.bridge

import com.rousecontext.mcp.core.INTERNAL_TOKEN_HEADER
import com.rousecontext.tunnel.MuxStream
import com.rousecontext.tunnel.TlsAcceptor
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
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
 * 4. Clean up on disconnect -- whichever direction finishes first, the other is
 *    unblocked by closing the local TCP socket in `finally` so the still-running
 *    side's blocking `read()` throws and terminates promptly.
 */
class SessionHandler(
    private val certProvider: TlsCertProvider,
    private val mcpSessionFactory: McpSessionFactory
) {

    /**
     * Handles a single incoming mux stream from the relay.
     *
     * Suspends until the session ends (remote disconnect, error, or cancellation).
     * Cleans up TLS, TCP, and MCP resources on exit.
     *
     * @throws IllegalStateException if no TLS certificate is available
     */
    suspend fun handleStream(stream: MuxStream) {
        val sslContext = certProvider.serverSslContext()
            ?: error("No TLS certificate available for server accept")

        val acceptor = TlsAcceptor.create(sslContext)
        val tlsSession = acceptor.accept(stream)

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
     * Both directions run as structured coroutines on [Dispatchers.IO]:
     *
     *  - TLS -> socket uses suspend [TlsAcceptor.TlsSession.read], so cancellation
     *    propagates cleanly.
     *  - Socket -> TLS still calls blocking `Socket.InputStream.read`, which cannot
     *    be cooperatively cancelled. We park that read on [Dispatchers.IO] and rely
     *    on closing the socket to unblock it.
     *
     * When either direction finishes it signals [done]; the surviving coroutine is
     * cancelled, then the socket is closed to unstick any still-blocked read.
     *
     * The socket close MUST run under [NonCancellable]: when the enclosing scope is
     * cancelled, a bare `withContext(Dispatchers.IO) { socket.close() }` would itself
     * throw [kotlinx.coroutines.CancellationException] before executing the close,
     * leaving `socketToTls` blocked on `InputStream.read` for up to 45 s (Ktor CIO's
     * `connectionIdleTimeoutSeconds` default) and stalling teardown. See issue #175.
     */
    private suspend fun bridgeToMcpServer(
        tlsSession: TlsAcceptor.TlsSession,
        mcpPort: Int,
        internalToken: String
    ) = coroutineScope {
        val socket = withContext(Dispatchers.IO) {
            Socket("127.0.0.1", mcpPort)
        }
        val done = CompletableDeferred<Unit>()
        try {
            val socketIn = socket.getInputStream()
            val socketOut = socket.getOutputStream()
            val injector = HttpHeaderInjector(
                "$INTERNAL_TOKEN_HEADER: $internalToken"
            )

            // TLS -> socket (client request bytes forwarded to MCP server).
            // The injector rewrites each request's header block to include
            // the shared secret that the local Ktor guard expects. See #177.
            val tlsToSocket = launch(Dispatchers.IO) {
                try {
                    copyTlsToStream(tlsSession, socketOut, injector)
                } finally {
                    done.complete(Unit)
                }
            }

            // Socket -> TLS (MCP server response bytes forwarded back through TLS).
            // The blocking Socket.InputStream.read cannot be cancelled cooperatively;
            // we rely on closing the socket in `finally` to unstick it.
            val socketToTls = launch(Dispatchers.IO) {
                try {
                    copyStreamToTls(socketIn, tlsSession)
                } finally {
                    done.complete(Unit)
                }
            }

            // Suspend until EITHER direction finishes (or we are cancelled).
            try {
                done.await()
            } finally {
                tlsToSocket.cancel()
                socketToTls.cancel()
            }
        } finally {
            // Close the socket even when the surrounding scope is being cancelled.
            // Must be NonCancellable -- see kdoc on [bridgeToMcpServer].
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    socket.close()
                } catch (_: Exception) {
                    // Best effort cleanup.
                }
            }
        }
    }

    /**
     * Copies plaintext bytes out of [tlsSession] and into [to] until EOF or error,
     * running every byte through [injector] so that each request's header block
     * is rewritten to include the `X-Internal-Token` shared secret.
     *
     * Must run under [Dispatchers.IO].
     */
    private suspend fun copyTlsToStream(
        tlsSession: TlsAcceptor.TlsSession,
        to: OutputStream,
        injector: HttpHeaderInjector
    ) {
        try {
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = tlsSession.read(buf)
                if (n == -1) break
                injector.feed(buf, 0, n) { out, off, len ->
                    to.write(out, off, len)
                }
                to.flush()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // Stream closed or peer errored -- treat as EOF.
        }
    }

    /**
     * Copies bytes from [from] into [tlsSession] until EOF or error.
     * Must run under [Dispatchers.IO] -- the underlying [InputStream.read] call is blocking.
     */
    private suspend fun copyStreamToTls(from: InputStream, tlsSession: TlsAcceptor.TlsSession) {
        try {
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = from.read(buf)
                if (n == -1) break
                tlsSession.write(buf, 0, n)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // Stream closed or peer errored -- treat as EOF.
        }
    }

    companion object {
        private const val BUFFER_SIZE = 8192
    }
}
