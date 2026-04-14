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
 * This is the core bridge logic extracted from integration tests. Each call
 * to [handleStream] processes one MCP client session end-to-end:
 *
 * 1. TLS accept over the mux stream (device is the TLS server)
 * 2. Start a local MCP HTTP session
 * 3. Bridge plaintext bytes between the TLS stream and the MCP session
 * 4. Clean up on disconnect
 */
class SessionHandler(
    private val certProvider: TlsCertProvider,
    private val mcpSessionFactory: McpSessionFactory
) {

    /**
     * Handles a single incoming mux stream from the relay.
     *
     * Blocks (suspends) until the session ends (remote disconnect, error, or cancellation).
     * Cleans up TLS, TCP, and MCP resources on exit.
     *
     * @throws IllegalStateException if no TLS certificate is available
     */
    suspend fun handleStream(stream: MuxStream) {
        val sslContext = certProvider.serverSslContext()
            ?: throw IllegalStateException("No TLS certificate available for server accept")

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
     * Uses daemon threads for the copy loops (blocking Java I/O) and suspends
     * until either direction closes or fails.
     */
    private suspend fun bridgeToMcpServer(
        plaintextIn: InputStream,
        plaintextOut: OutputStream,
        mcpPort: Int
    ) {
        val socket = withContext(Dispatchers.IO) {
            Socket("127.0.0.1", mcpPort)
        }

        val done = CompletableDeferred<Unit>()

        try {
            val socketIn = socket.getInputStream()
            val socketOut = socket.getOutputStream()

            // plaintext -> socket (client request bytes forwarded to MCP server)
            Thread {
                try {
                    val buf = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val n = plaintextIn.read(buf)
                        if (n == -1) break
                        socketOut.write(buf, 0, n)
                        socketOut.flush()
                    }
                } catch (_: Exception) {
                    // Stream closed
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
                    val buf = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val n = socketIn.read(buf)
                        if (n == -1) break
                        plaintextOut.write(buf, 0, n)
                        plaintextOut.flush()
                    }
                } catch (_: Exception) {
                    // Stream closed
                } finally {
                    done.complete(Unit)
                }
            }.apply {
                isDaemon = true
                name = "bridge-mcp-to-plaintext"
                start()
            }

            // Suspend until either copy loop ends
            done.await()
        } finally {
            withContext(Dispatchers.IO) {
                try {
                    socket.close()
                } catch (_: Exception) {
                    // Best effort cleanup
                }
            }
        }
    }

    companion object {
        private const val BUFFER_SIZE = 8192
    }
}
