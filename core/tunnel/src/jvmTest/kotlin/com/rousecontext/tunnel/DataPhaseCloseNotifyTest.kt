package com.rousecontext.tunnel

import com.rousecontext.bridge.McpSessionFactory
import com.rousecontext.bridge.McpSessionHandle
import com.rousecontext.bridge.SessionHandler
import com.rousecontext.bridge.TlsCertProvider
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLEngineResult.Status
import javax.net.ssl.SSLSession
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Timeout

/**
 * A `close_notify` in the **data phase** is one event, whichever direction meets
 * it (#649).
 *
 * ## What this pins, and why it needed pinning
 *
 * `SSLEngineResult.Status.CLOSED` reaches `TlsAcceptor.kt` from four sites. Two
 * distinctions between them are load-bearing and stay -- handshake versus data
 * phase (#618, #643), and peer behaviour versus our own defect (#616, #630).
 * The third was not: the data-phase read and write paths were classified
 * independently, a day apart, and ended up looking different.
 *
 * They still *look* different, and must: `read` returns an `Int` and says "-1",
 * `write` returns `Unit` and has to throw. What #649 changed is that they are
 * now one decision expressed twice rather than two decisions that happened to
 * agree -- and the agreement was load-bearing, because it rested entirely on
 * `SessionHandler`'s broad `catch (_: Exception)` swallowing the write-side
 * throw into the same silence as the read-side `-1`.
 *
 * **Nothing tested that coupling.** The type at the throw site was effectively
 * chosen by what the consumer happened to do with it, so an edit to either side
 * alone would have diverged them silently: give `copyStreamToTls` a
 * `catch (e: IOException) { throw e }` and every ordinary peer hang-up on a
 * response becomes a crash report, while the identical event on the request
 * side stays quiet. These two tests fail if that happens.
 *
 * The boundary test overlaps deliberately with
 * [WrapDefectReachesSessionHandlerTest]'s quiet-path test (#639), which pins the
 * write direction on its own. The point here is the *pair*: both directions
 * asserted side by side, so "these are the same event" is a thing a test says
 * rather than a thing a comment claims.
 *
 * "Quietly" means "`handleStream` returns normally": this is a KMP jvm target
 * with no Android `Log` or `CrashReporter` on the classpath, and
 * `TunnelForegroundService.collectIncomingSessions` wraps each `handleStream`
 * call in `catch (e: Exception) { Log.e(...); crashReporter.logCaughtException(e) }`.
 * Returning normally is therefore exactly "no error log, no crash report".
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class DataPhaseCloseNotifyTest {

    private val servers = mutableListOf<ServerSocket>()
    private val accepted = mutableListOf<Socket>()

    @AfterTest
    fun tearDown() {
        accepted.forEach { runCatching { it.close() } }
        servers.forEach { runCatching { it.close() } }
    }

    @Test
    fun `a data-phase CLOSED is end of stream in both directions`() = runBlocking {
        val readSession = sessionUnwrapping(Status.CLOSED)

        assertEquals(
            -1,
            withTimeout(TIMEOUT_MS) { readSession.read(ByteArray(READ_BUFFER)) },
            "a close_notify on an established session is the ordinary end of that " +
                "session: read must report EOF, not raise anything"
        )

        val writeSession = sessionWrapping(Status.CLOSED)
        val thrown = assertFailsWith<IOException> {
            withTimeout(TIMEOUT_MS) { writeSession.write(PAYLOAD) }
        }

        // Reflective, not `is TunnelError`: the compiler already knows an
        // IOException cannot be one.
        assertFalse(
            TunnelError::class.java.isInstance(thrown),
            "the same event must not be a tunnel defect on the write side, got: $thrown"
        )
        assertTrue(
            thrown is TlsStreamClosed,
            "the write side must name the event it is reporting rather than leaving " +
                "its meaning to whatever the consumer does with a bare IOException, got: $thrown"
        )
    }

    @Test
    fun `a data-phase CLOSED ends the session quietly in both directions`() = runBlocking {
        // Read direction: the engine reports CLOSED from unwrap, so
        // `copyTlsToStream` sees -1 and stops. Nothing is written to the write
        // direction, which parks on the loopback socket until teardown.
        withTimeout(TIMEOUT_MS) {
            handlerUnwrapping(Status.CLOSED).handleStream(ClosingMuxStream())
        }

        // Write direction: the same status, met by `wrap` this time, reached by
        // pushing bytes at the socket -> TLS copy loop. The read direction parks
        // on the mux stream.
        val server = newServer()
        val fed = feedTheWriteDirection(server)
        withTimeout(TIMEOUT_MS) {
            handlerWrapping(Status.CLOSED, server).handleStream(ParkedMuxStream())
        }
        withTimeout(TIMEOUT_MS) { fed.await() }

        coroutineContext.cancelChildren()
    }

    // ------------------------------------------------------------- helpers

    private fun newServer(): ServerSocket = ServerSocket(0).also { servers += it }

    /**
     * Accepts the bridge's loopback connection and pushes a byte at it so the
     * socket -> TLS direction actually reaches `tlsSession.write`. The socket is
     * held open so the copy loop cannot see EOF before the write.
     */
    private fun CoroutineScope.feedTheWriteDirection(
        server: ServerSocket
    ): CompletableDeferred<Unit> {
        val fed = CompletableDeferred<Unit>()
        launch(Dispatchers.IO) {
            val peer = server.accept()
            accepted += peer
            peer.getOutputStream().apply {
                write("response bytes".toByteArray())
                flush()
            }
            fed.complete(Unit)
        }
        return fed
    }

    private suspend fun sessionUnwrapping(status: Status): TlsAcceptor.TlsSession =
        TlsAcceptor.create(ScriptedSslContext(UnwrapStatusEngine(nullSession(), status)))
            .accept(ClosingMuxStream())

    private suspend fun sessionWrapping(status: Status): TlsAcceptor.TlsSession =
        TlsAcceptor.create(ScriptedSslContext(WrapStatusEngine(nullSession(), status)))
            .accept(ParkedMuxStream())

    private fun handlerUnwrapping(status: Status): SessionHandler =
        handlerOver(UnwrapStatusEngine(nullSession(), status), newServer())

    private fun handlerWrapping(status: Status, server: ServerSocket): SessionHandler =
        handlerOver(WrapStatusEngine(nullSession(), status), server)

    /** A handler whose TLS path is the production one, over a scripted engine. */
    private fun handlerOver(engine: ScriptedSslEngine, server: ServerSocket) = SessionHandler(
        certProvider = ScriptedCertProvider(ScriptedSslContext(engine)),
        mcpSessionFactory = LoopbackMcpSessionFactory(server.localPort)
        // tlsAccept deliberately left at its production default.
    )

    private fun nullSession(): SSLSession = ScriptedSslEngine.nullSession()

    private class ScriptedCertProvider(private val context: SSLContext) : TlsCertProvider {
        override suspend fun serverSslContext(): SSLContext = context
    }

    private class LoopbackMcpSessionFactory(private val port: Int) : McpSessionFactory {
        override suspend fun create(): McpSessionHandle = McpSessionHandle(
            port = port,
            internalToken = "test-token",
            stop = {}
        )
    }

    // ------------------------------------------------------------- engines

    /**
     * Handshake-free engine reporting [status] from every `unwrap`, consuming
     * nothing. The write path is never exercised by these scripts.
     */
    private class UnwrapStatusEngine(session: SSLSession, private val status: Status) :
        ScriptedSslEngine(session) {

        override fun getHandshakeStatus(): HandshakeStatus = HandshakeStatus.NOT_HANDSHAKING

        override fun scriptedWrap(src: ByteBuffer, dst: ByteBuffer): SSLEngineResult =
            error("unwrap-status script: the write path is not exercised here")

        override fun scriptedUnwrap(src: ByteBuffer, dst: ByteBuffer): SSLEngineResult {
            check(unwrapCalls <= MAX_NO_PROGRESS_CALLS) {
                "read() re-unwrapped the same bytes $unwrapCalls times on a $status status " +
                    "without making progress"
            }
            return SSLEngineResult(status, HandshakeStatus.NOT_HANDSHAKING, 0, 0)
        }
    }

    /** Handshake-free engine reporting [status] from every `wrap`, producing nothing. */
    private class WrapStatusEngine(session: SSLSession, private val status: Status) :
        ScriptedSslEngine(session) {

        override fun getHandshakeStatus(): HandshakeStatus = HandshakeStatus.NOT_HANDSHAKING

        override fun scriptedUnwrap(src: ByteBuffer, dst: ByteBuffer): SSLEngineResult =
            error("wrap-status script: the read path parks on the mux stream")

        override fun scriptedWrap(src: ByteBuffer, dst: ByteBuffer): SSLEngineResult {
            check(wrapCalls <= MAX_NO_PROGRESS_CALLS) {
                "write() re-wrapped the same bytes $wrapCalls times on a $status status " +
                    "without classifying it"
            }
            return SSLEngineResult(status, HandshakeStatus.NOT_HANDSHAKING, 0, 0)
        }
    }

    // ------------------------------------------------------------- streams

    /** Yields one DATA frame -- enough to reach `unwrap` -- then parks. */
    private class ClosingMuxStream : MuxStream {
        override val id: UInt = 1u
        override val incoming: Flow<ByteArray> = emptyFlow()
        override val isClosed: Boolean = false

        private var served = false

        override suspend fun send(data: ByteArray) = Unit

        override suspend fun close() = Unit

        override suspend fun read(): ByteArray {
            if (served) awaitCancellation()
            served = true
            return "ciphertext".toByteArray()
        }
    }

    /** Never yields inbound data: the TLS -> socket direction parks here. */
    private class ParkedMuxStream : MuxStream {
        override val id: UInt = 1u
        override val incoming: Flow<ByteArray> = emptyFlow()
        override val isClosed: Boolean = false

        override suspend fun send(data: ByteArray) = Unit

        override suspend fun close() = Unit

        override suspend fun read(): ByteArray = awaitCancellation()
    }

    private companion object {
        val PAYLOAD = "plaintext to encrypt".toByteArray()
        const val READ_BUFFER = 16
        const val TIMEOUT_MS = 15_000L
        const val MAX_NO_PROGRESS_CALLS = 20
    }
}
