package com.rousecontext.bridge

import com.rousecontext.tunnel.MuxStream
import com.rousecontext.tunnel.TlsAcceptor
import com.rousecontext.tunnel.TunnelError
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketException
import javax.net.ssl.SSLContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.rules.Timeout

/**
 * The copy loops in [SessionHandler] have to hold two things apart:
 *
 *  - a peer hanging up mid-session, which is normal, frequent, and must end the
 *    session quietly; and
 *  - [TunnelError.UnhandledTlsState], which says our own TLS layer reached a
 *    state it has no handling for. That is a defect and must stay visible.
 *
 * Before #616 a bare `catch (_: Exception)` filed both as a clean EOF -- which
 * is bit for bit what the `return -1` that #615 replaced used to produce, so
 * the guard existed and could not be heard. These tests pin BOTH directions:
 * the defect must escape [SessionHandler.handleStream], and an ordinary
 * disconnect must still not.
 *
 * `:core:bridge` is a KMP jvm target with no Android `Log` or `CrashReporter`
 * on its classpath, so "observable" here means "propagates out of
 * `handleStream`", where `TunnelForegroundService.collectIncomingSessions`
 * already does `Log.e` + `crashReporter.logCaughtException` per stream.
 */
class SessionHandlerDefectVisibilityTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(TEST_TIMEOUT_SECONDS)

    /** Stand-in for the local MCP server: accepts the bridge's loopback connection. */
    private val mcpServer = ServerSocket(0)

    @AfterTest
    fun tearDown() {
        mcpServer.close()
    }

    @Test
    fun `handleStream surfaces an unhandled TLS state instead of ending quietly`() = runBlocking {
        val defect = TunnelError.UnhandledTlsState(UNWRAP_MESSAGE)
        val handler = handlerWith(StubTlsSession(onRead = { throw defect }))

        val thrown = runCatching {
            withTimeout(TIMEOUT_MS) { handler.handleStream(UnusedMuxStream()) }
        }.exceptionOrNull()

        if (thrown == null) {
            fail(
                "handleStream returned normally: the unhandled-TLS-state defect " +
                    "was swallowed back into a clean EOF (#616)."
            )
        }
        assertTrue(
            thrown is TunnelError.UnhandledTlsState,
            "Expected TunnelError.UnhandledTlsState at the SessionHandler boundary, got $thrown"
        )
        assertEquals(UNWRAP_MESSAGE, thrown.message)

        coroutineContext.cancelChildren()
    }

    @Test
    fun `handleStream treats an ordinary peer disconnect as a quiet EOF`() = runBlocking {
        // The common case by a wide margin. It must NOT become noisy: training
        // whoever reads the logs to ignore them is the same failure one level up.
        val handler = handlerWith(
            StubTlsSession(onRead = { throw SocketException("Connection reset") })
        )

        withTimeout(TIMEOUT_MS) { handler.handleStream(UnusedMuxStream()) }

        coroutineContext.cancelChildren()
    }

    @Test
    fun `a plain IOException from the TLS layer is still a quiet EOF`() = runBlocking {
        // Exactly what SuspendTlsSession.write reports for a peer that went
        // away, and the reason a bare IOException cannot be the discriminator.
        val handler = handlerWith(
            StubTlsSession(onRead = { throw IOException("TLS write failed: stream closed") })
        )

        withTimeout(TIMEOUT_MS) { handler.handleStream(UnusedMuxStream()) }

        coroutineContext.cancelChildren()
    }

    @Test
    fun `handleStream surfaces an unhandled TLS state from the write direction`() = runBlocking {
        val defect = TunnelError.UnhandledTlsState("Unhandled TLS wrap status: FAKE")
        val handler = handlerWith(
            StubTlsSession(
                onRead = { awaitCancellation() },
                onWrite = { throw defect }
            )
        )

        // Give the socket -> TLS direction something to write.
        launch(Dispatchers.IO) {
            mcpServer.accept().use { it.getOutputStream().write("hello".toByteArray()) }
        }

        val thrown = runCatching {
            withTimeout(TIMEOUT_MS) { handler.handleStream(UnusedMuxStream()) }
        }.exceptionOrNull()

        assertTrue(
            thrown is TunnelError.UnhandledTlsState,
            "Expected the write-direction defect at the SessionHandler boundary, got $thrown"
        )

        coroutineContext.cancelChildren()
    }

    @Test
    fun `cancellation still propagates through the copy loops`() = runBlocking {
        val readEntered = CompletableDeferred<Unit>()
        val readCancelled = CompletableDeferred<Unit>()
        val handler = handlerWith(
            StubTlsSession(
                onRead = {
                    readEntered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        readCancelled.complete(Unit)
                    }
                }
            )
        )

        val session = async(Dispatchers.IO) { handler.handleStream(UnusedMuxStream()) }
        withTimeout(TIMEOUT_MS) { readEntered.await() }

        session.cancel()

        withTimeout(TIMEOUT_MS) { readCancelled.await() }
        withTimeout(TIMEOUT_MS) { session.join() }
        assertTrue(session.isCancelled, "handleStream should complete as cancelled, not normally")

        coroutineContext.cancelChildren()
    }

    // -- helpers --

    private fun handlerWith(session: TlsAcceptor.TlsSession) = SessionHandler(
        certProvider = DefaultSslContextProvider(),
        mcpSessionFactory = LoopbackMcpSessionFactory(mcpServer.localPort),
        tlsAccept = { _, _ -> session }
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

    private class StubTlsSession(
        private val onRead: suspend () -> Int,
        private val onWrite: suspend () -> Unit = { awaitCancellation() }
    ) : TlsAcceptor.TlsSession {
        override suspend fun read(buf: ByteArray, off: Int, len: Int): Int = onRead()

        override suspend fun write(buf: ByteArray, off: Int, len: Int) = onWrite()

        override suspend fun close() = Unit
    }

    private class UnusedMuxStream : MuxStream {
        override val id: UInt = 1u
        override val incoming: Flow<ByteArray> = emptyFlow()
        override val isClosed: Boolean = false

        override suspend fun send(data: ByteArray) = Unit

        override suspend fun close() = Unit

        override suspend fun read(): ByteArray = awaitCancellation()
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val UNWRAP_MESSAGE = "Unhandled TLS unwrap status: FAKE"
    }
}
