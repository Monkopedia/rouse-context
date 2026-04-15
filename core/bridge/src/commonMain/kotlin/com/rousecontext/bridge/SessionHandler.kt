package com.rousecontext.bridge

import com.rousecontext.tunnel.MuxStream
import com.rousecontext.tunnel.TlsAcceptor
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
 * 4. Clean up on disconnect — whichever direction finishes first, the other is
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
                plaintextIn = tlsSession.input,
                plaintextOut = tlsSession.output,
                mcpPort = mcpHandle.port
            )
        } finally {
            mcpHandle.stop()
        }
    }

    /**
     * Bridges TLS plaintext I/O streams to a local MCP server via TCP.
     *
     * Two daemon threads run the byte-copy loops because JVM blocking I/O
     * (`InputStream.read`) cannot be cooperatively cancelled — the only reliable
     * way to unstick a blocked read is to close the underlying socket. When
     * either side signals completion via the shared [CompletableDeferred], the
     * `finally` block closes the TCP socket, which makes the still-running
     * thread's `read` throw and terminate.
     *
     * Wrapped in [Dispatchers.IO] so the blocking [Socket] constructor and
     * close do not fire coroutine blocking-call warnings.
     */
    private suspend fun bridgeToMcpServer(
        plaintextIn: InputStream,
        plaintextOut: OutputStream,
        mcpPort: Int
    ) = withContext(Dispatchers.IO) {
        val socket = Socket("127.0.0.1", mcpPort)
        val done = CompletableDeferred<Unit>()
        try {
            val socketIn = socket.getInputStream()
            val socketOut = socket.getOutputStream()

            // plaintext -> socket (client request bytes forwarded to MCP server)
            Thread {
                try {
                    copyLoop(plaintextIn, socketOut)
                } finally {
                    done.complete(Unit)
                }
            }.apply {
                isDaemon = true
                name = "bridge-plaintext-to-mcp"
                start()
            }

            // socket -> plaintext (MCP server response bytes forwarded back through TLS)
            Thread {
                try {
                    copyLoop(socketIn, plaintextOut)
                } finally {
                    done.complete(Unit)
                }
            }.apply {
                isDaemon = true
                name = "bridge-mcp-to-plaintext"
                start()
            }

            // Suspend until EITHER direction finishes. This is the key fix for #128:
            // the old :app.McpSessionBridge did `clientToServer.join(); serverToClient.cancel()`
            // which hung when the server side finished first.
            done.await()
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
                // Best effort cleanup — the surviving copy thread will see the close
                // as an exception on its next read and exit.
            }
        }
    }

    /**
     * Copies bytes from [from] to [to] until EOF or the stream errors.
     *
     * Swallows read/write exceptions so the caller treats "stream closed" and
     * "stream errored" the same way. Must be called on a thread that permits
     * blocking I/O.
     */
    private fun copyLoop(from: InputStream, to: OutputStream) {
        try {
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = from.read(buf)
                if (n == -1) break
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (_: Exception) {
            // Stream closed or peer errored — treat as EOF.
        }
    }

    companion object {
        private const val BUFFER_SIZE = 8192
    }
}
