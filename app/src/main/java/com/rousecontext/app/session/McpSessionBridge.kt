package com.rousecontext.app.session

import android.util.Log
import com.rousecontext.mcp.core.McpSession
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.MuxStream
import com.rousecontext.tunnel.TlsAcceptor
import com.rousecontext.work.SessionHandler
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bridges incoming mux streams to the MCP HTTP server.
 *
 * For each stream:
 * 1. Performs TLS server-side accept (device is the TLS server)
 * 2. Bridges the plaintext I/O to a TCP connection to the local Ktor HTTP server
 * 3. Suspends until the session completes
 *
 * The [McpSession] must be started before this bridge handles any streams.
 */
class McpSessionBridge(
    private val mcpSession: McpSession,
    private val certStore: CertificateStore
) : SessionHandler {

    @Volatile
    private var tlsAcceptor: TlsAcceptor? = null

    @Volatile
    private var resolvedPort: Int = -1

    override suspend fun handle(stream: MuxStream) {
        ensurePortResolved()
        val acceptor = getOrCreateAcceptor()
        Log.i(TAG, "Starting TLS accept for stream ${stream.id}")

        val tlsSession = acceptor.accept(stream)
        Log.i(TAG, "TLS handshake complete for stream ${stream.id}")

        bridgeToMcpServer(tlsSession.input, tlsSession.output, stream)
    }

    private suspend fun ensurePortResolved() {
        if (resolvedPort > 0) return
        resolvedPort = mcpSession.resolvePort()
        check(resolvedPort > 0) { "MCP session not started or port not resolved" }
        Log.i(TAG, "MCP server resolved to port $resolvedPort")
    }

    private suspend fun getOrCreateAcceptor(): TlsAcceptor {
        tlsAcceptor?.let { return it }

        val certPem = certStore.getCertificate()
            ?: throw IllegalStateException("No server certificate available")
        val keyPem = certStore.getPrivateKey()
            ?: throw IllegalStateException("No private key available")

        val acceptor = TlsAcceptor.fromPem(certPem, keyPem)
        tlsAcceptor = acceptor
        return acceptor
    }

    /**
     * Bridges TLS plaintext I/O to a TCP socket connected to the local MCP Ktor server.
     *
     * Two copy loops run concurrently:
     * - plaintext input (from client via TLS) -> socket output (to Ktor)
     * - socket input (from Ktor) -> plaintext output (to client via TLS)
     *
     * Suspends until the stream closes or an error occurs.
     */
    private suspend fun bridgeToMcpServer(
        plaintextIn: InputStream,
        plaintextOut: OutputStream,
        stream: MuxStream
    ) = coroutineScope {
        val port = resolvedPort
        Log.i(TAG, "Bridging stream ${stream.id} to MCP server on port $port")

        val socket = withContext(Dispatchers.IO) {
            Socket("127.0.0.1", port)
        }

        try {
            val socketIn = socket.getInputStream()
            val socketOut = socket.getOutputStream()

            // Client -> MCP server
            val clientToServer = launch(Dispatchers.IO) {
                try {
                    val buf = ByteArray(BUFFER_SIZE)
                    while (isActive) {
                        val n = plaintextIn.read(buf)
                        if (n == -1) break
                        socketOut.write(buf, 0, n)
                        socketOut.flush()
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Client->server copy ended for stream ${stream.id}: ${e.message}")
                }
            }

            // MCP server -> Client
            val serverToClient = launch(Dispatchers.IO) {
                try {
                    val buf = ByteArray(BUFFER_SIZE)
                    while (isActive) {
                        val n = socketIn.read(buf)
                        if (n == -1) break
                        plaintextOut.write(buf, 0, n)
                        plaintextOut.flush()
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Server->client copy ended for stream ${stream.id}: ${e.message}")
                }
            }

            // Wait for either direction to finish, then cancel the other
            clientToServer.join()
            serverToClient.cancel()
            serverToClient.join()
        } finally {
            withContext(Dispatchers.IO) {
                try {
                    socket.close()
                } catch (_: Exception) {
                    // Best-effort cleanup
                }
            }
            try {
                stream.close()
            } catch (_: Exception) {
                // Best-effort cleanup
            }
            Log.i(TAG, "Bridge closed for stream ${stream.id}")
        }
    }

    /**
     * Invalidates the cached TLS acceptor, forcing re-creation on next use.
     * Call after certificate renewal.
     */
    fun invalidateTlsAcceptor() {
        tlsAcceptor = null
    }

    companion object {
        private const val TAG = "McpSessionBridge"
        private const val BUFFER_SIZE = 8192
    }
}
