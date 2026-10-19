package com.rousecontext.bridge

import com.rousecontext.mcp.core.InMemoryProviderRegistry
import com.rousecontext.mcp.core.InMemoryTokenStore
import com.rousecontext.tunnel.MuxDemux
import com.rousecontext.tunnel.MuxFrame
import com.rousecontext.tunnel.MuxStream
import com.rousecontext.tunnel.TlsAcceptor
import com.rousecontext.tunnel.TunnelError
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Rule
import org.junit.rules.Timeout

/**
 * Regression gate for #780: when a session ended on the device side --
 * the #778 idle bound, an error, the local server closing its connection --
 * `SessionHandler` never closed the mux stream, so no CLOSE frame went to the
 * relay. The relay therefore kept the client's TCP connection open (the client
 * saw neither more bytes nor EOF until its own timeout) and kept holding the
 * per-device stream slot.
 *
 * The frame-level tests drive a real [MuxDemux] and count the CLOSE frames it
 * emits, so "exactly one" and "none on a peer close" are measured against the
 * real stream's idempotency rather than a stub's. The TLS layer is a plaintext
 * stand-in: what is under test is the stream's lifecycle, not the handshake.
 */
class SessionHandlerStreamCloseTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(TEST_TIMEOUT_SECONDS)

    /** Stand-in for the local MCP server: connections land in its backlog and sit. */
    private val silentMcpServer = ServerSocket(0)

    private val demux = MuxDemux()
    private val sentFrames = ConcurrentLinkedQueue<MuxFrame>()

    init {
        demux.onOutgoingFrame = { sentFrames.add(it) }
    }

    @AfterTest
    fun tearDown() {
        silentMcpServer.close()
    }

    @Test
    fun `a session that idles out sends CLOSE for its stream`() = runBlocking {
        val stream = openStream()
        val handler = plaintextHandler(idleTimeout = SHORT_IDLE)

        withTimeout(TIMEOUT_MS) { handler.handleStream(stream) }

        assertEquals(
            1,
            closesSent(),
            "idle-limit end must send exactly one CLOSE; sent $sentFrames"
        )
        assertEquals(0, demux.activeStreamCount.value, "stream must leave the demux")
    }

    @Test
    fun `a session that ends on a TLS defect still sends CLOSE`() = runBlocking {
        val stream = openStream()
        val handler = handlerWith { PlaintextTlsSession(stream, onRead = { throw DEFECT }) }

        val thrown = runCatching {
            withTimeout(TIMEOUT_MS) { handler.handleStream(stream) }
        }.exceptionOrNull()

        assertTrue(
            thrown is TunnelError.UnhandledTlsState,
            "the defect must still propagate; got $thrown"
        )
        assertEquals(1, closesSent(), "error end must send exactly one CLOSE; sent $sentFrames")
        assertEquals(0, demux.activeStreamCount.value, "stream must leave the demux")
    }

    @Test
    fun `a failed TLS accept still sends CLOSE`() = runBlocking {
        val stream = openStream()
        val handler = SessionHandler(
            certProvider = DefaultSslContextProvider(),
            mcpSessionFactory = LoopbackMcpSessionFactory(silentMcpServer.localPort),
            tlsAccept = { _, _ -> throw TunnelError.TlsHandshakeFailed("handshake failed") }
        )

        val thrown = runCatching {
            withTimeout(TIMEOUT_MS) { handler.handleStream(stream) }
        }.exceptionOrNull()

        assertTrue(thrown is TunnelError.TlsHandshakeFailed, "got $thrown")
        assertEquals(
            1,
            closesSent(),
            "a failed accept must send exactly one CLOSE; sent $sentFrames"
        )
    }

    @Test
    fun `a session cancelled on the device still sends CLOSE`() = runBlocking {
        val stream = openStream()
        val readEntered = CompletableDeferred<Unit>()
        val handler = handlerWith {
            PlaintextTlsSession(stream, onRead = { readEntered.complete(Unit) })
        }

        val session = launch(Dispatchers.IO) { handler.handleStream(stream) }
        withTimeout(TIMEOUT_MS) { readEntered.await() }
        session.cancel()
        withTimeout(TIMEOUT_MS) { session.join() }

        assertEquals(1, closesSent(), "cancellation must send exactly one CLOSE; sent $sentFrames")
        assertEquals(0, demux.activeStreamCount.value, "stream must leave the demux")
    }

    @Test
    fun `a client-initiated close sends no second CLOSE`() = runBlocking {
        val stream = openStream()
        val readEntered = CompletableDeferred<Unit>()
        val handler = handlerWith {
            PlaintextTlsSession(stream, onRead = { readEntered.complete(Unit) })
        }

        val session = launch(Dispatchers.IO) { handler.handleStream(stream) }
        withTimeout(TIMEOUT_MS) { readEntered.await() }

        // The relay's CLOSE: the client hung up.
        demux.handleFrame(MuxFrame.Close(STREAM_ID))
        withTimeout(TIMEOUT_MS) { session.join() }

        assertEquals(
            0,
            closesSent(),
            "the relay already closed this stream; the device must not echo CLOSE. Sent $sentFrames"
        )
        assertEquals(0, demux.activeStreamCount.value, "stream must leave the demux")
    }

    // -- helpers --

    private suspend fun openStream(): MuxStream {
        demux.handleFrame(MuxFrame.Open(STREAM_ID))
        return demux.incomingStreams.first()
    }

    private fun closesSent(): Int =
        sentFrames.count { it is MuxFrame.Close && it.streamId == STREAM_ID }

    private fun plaintextHandler(idleTimeout: Duration) = SessionHandler(
        certProvider = DefaultSslContextProvider(),
        mcpSessionFactory = LoopbackMcpSessionFactory(silentMcpServer.localPort),
        idleTimeout = idleTimeout,
        tlsAccept = { _, stream -> PlaintextTlsSession(stream) }
    )

    private fun handlerWith(session: () -> TlsAcceptor.TlsSession) = SessionHandler(
        certProvider = DefaultSslContextProvider(),
        mcpSessionFactory = LoopbackMcpSessionFactory(silentMcpServer.localPort),
        tlsAccept = { _, _ -> session() }
    )

    private class DefaultSslContextProvider : TlsCertProvider {
        override suspend fun serverSslContext(): SSLContext = SSLContext.getDefault()
    }

    private class LoopbackMcpSessionFactory(private val port: Int) : McpSessionFactory {
        override suspend fun create(): McpSessionHandle = McpSessionHandle(
            port = port,
            internalToken = "test-token",
            stop = {}
        )
    }

    /**
     * Plaintext stand-in for a TLS session over [stream]: a peer CLOSE reads
     * as EOF, as it does on the real session. [close] is deliberately a no-op
     * so the tests cannot pass on the strength of a TLS-layer close that
     * production might not route through.
     */
    private class PlaintextTlsSession(
        private val stream: MuxStream,
        private val onRead: suspend () -> Unit = {}
    ) : TlsAcceptor.TlsSession {
        override suspend fun read(buf: ByteArray, off: Int, len: Int): Int {
            onRead()
            val chunk = try {
                stream.read()
            } catch (_: ClosedReceiveChannelException) {
                null
            }
            chunk?.copyInto(buf, off)
            return chunk?.size ?: -1
        }

        override suspend fun write(buf: ByteArray, off: Int, len: Int) {
            stream.send(buf.copyOfRange(off, off + len))
        }

        override suspend fun close() = Unit
    }

    private companion object {
        const val STREAM_ID = 1u
        const val TIMEOUT_MS = 10_000L
        val SHORT_IDLE = 300.milliseconds
        val DEFECT = TunnelError.UnhandledTlsState("Unhandled TLS unwrap status: FAKE")
    }
}

/**
 * The client-visible half of #780, end to end over real TLS: when the local
 * server ends the connection after a response, the client must see EOF rather
 * than an open connection that never says anything again.
 */
class SessionHandlerDeviceEndEofTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(TEST_TIMEOUT_SECONDS)

    private val certStore = TestCertHelper()
    private val tokenStore = InMemoryTokenStore()
    private val token = tokenStore.createTokenPair("test", "eof-test-client").accessToken
    private val registry = InMemoryProviderRegistry().apply {
        register("test", EchoProvider())
        setEnabled("test", true)
    }
    private val clientPool: ExecutorService = Executors.newSingleThreadExecutor {
        Thread(it, "eof-test-client").apply { isDaemon = true }
    }
    private val clientDispatcher = clientPool.asCoroutineDispatcher()

    @AfterTest
    fun tearDown() {
        clientPool.shutdownNow()
    }

    @Test
    fun `a connection the device ends reaches the client as EOF after the response`() =
        runBlocking {
            val handler = SessionHandler(
                certProvider = certStore,
                mcpSessionFactory = KtorMcpSessionFactory(registry, tokenStore)
            )
            val (serverStream, clientStream) = createMuxPipe(1u)
            val session = launch { handler.handleStream(serverStream) }

            val exchange = async(clientDispatcher) {
                val (input, out) = tlsClientHandshake(certStore, clientStream)
                // Connection: close makes the local server end the connection after
                // responding -- the ordinary device-side end of a session.
                out.write(closingInitializeRequest())
                out.flush()
                val received = ByteArrayOutputStream()
                val buf = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) break
                    received.write(buf, 0, n)
                }
                received.toString(Charsets.UTF_8)
            }
            val response = withTimeoutOrNull(EOF_TIMEOUT_MS) { exchange.await() }
            // Unblock the client if the device never closed, so a red run fails on
            // the assertion below rather than on the JUnit timeout.
            serverStream.close()

            assertNotNull(
                response,
                "client got no EOF: the device ended the session without closing the stream"
            )
            assertTrue(
                response.startsWith("HTTP/1.1 200 OK"),
                "positive control: response was\n$response"
            )
            withTimeout(EOF_TIMEOUT_MS) { session.join() }

            coroutineContext.cancelChildren()
        }

    private fun closingInitializeRequest(): ByteArray {
        val body = INITIALIZE_BODY.toByteArray()
        val head = "POST /mcp HTTP/1.1\r\n" +
            "Host: test.rousecontext.com\r\n" +
            "Content-Type: application/json\r\n" +
            "Accept: application/json\r\n" +
            "Authorization: Bearer $token\r\n" +
            "Connection: close\r\n" +
            "Content-Length: ${body.size}\r\n\r\n"
        return head.toByteArray() + body
    }

    private companion object {
        const val EOF_TIMEOUT_MS = 10_000L
        const val BUFFER = 8192
        const val INITIALIZE_BODY =
            """{"jsonrpc":"2.0","method":"initialize","params":{"protocolVersion":"2025-03-26",""" +
                """"capabilities":{},"clientInfo":{"name":"eof-test","version":"1.0"}},"id":1}"""
    }
}
