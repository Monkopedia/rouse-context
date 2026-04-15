package com.rousecontext.bridge

import com.rousecontext.tunnel.MuxStream
import com.rousecontext.tunnel.TlsAcceptor
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
                mcpPort = mcpHandle.port
            )
        } finally {
            mcpHandle.stop()
        }
    }

    /**
     * Bridges a suspend-native [TlsAcceptor.TlsSession] to a local MCP server via TCP.
     *
     * Both directions now run as structured coroutines on [Dispatchers.IO]:
     *
     *  - TLS -> socket uses suspend [TlsAcceptor.TlsSession.read], so cancellation
     *    propagates cleanly.
     *  - Socket -> TLS still calls blocking `Socket.InputStream.read`, which cannot
     *    be cooperatively cancelled. We park that read on [Dispatchers.IO] and rely
     *    on closing the socket in `finally` to unblock it, the same teardown
     *    strategy the previous `Thread {}` implementation used.
     *
     * When either direction finishes it signals [done]; the surviving coroutine is
     * cancelled, then the socket is closed to unstick any still-blocked read.
     */
    private suspend fun bridgeToMcpServer(tlsSession: TlsAcceptor.TlsSession, mcpPort: Int) =
        coroutineScope {
            val socket = withContext(Dispatchers.IO) {
                Socket("127.0.0.1", mcpPort)
            }
            val done = CompletableDeferred<Unit>()
            try {
                val socketIn = socket.getInputStream()
                val socketOut = socket.getOutputStream()

                // TLS -> socket (client request bytes forwarded to MCP server).
                val tlsToSocket = launch(Dispatchers.IO) {
                    try {
                        copyTlsToStream(tlsSession, socketOut)
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

                // Suspend until EITHER direction finishes.
                done.await()
                tlsToSocket.cancel()
                socketToTls.cancel()
            } finally {
                try {
                    withContext(Dispatchers.IO) { socket.close() }
                } catch (_: Exception) {
                    // Best effort cleanup.
                }
            }
        }

    /**
     * Copies plaintext bytes out of [tlsSession] and into [to] until EOF or error.
     * Must run under [Dispatchers.IO].
     */
    private suspend fun copyTlsToStream(tlsSession: TlsAcceptor.TlsSession, to: OutputStream) {
        try {
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = tlsSession.read(buf)
                if (n == -1) break
                to.write(buf, 0, n)
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
