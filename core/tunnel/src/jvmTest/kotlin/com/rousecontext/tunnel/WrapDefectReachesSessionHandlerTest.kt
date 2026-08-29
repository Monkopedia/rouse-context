package com.rousecontext.tunnel

import com.rousecontext.bridge.McpSessionFactory
import com.rousecontext.bridge.McpSessionHandle
import com.rousecontext.bridge.SessionHandler
import com.rousecontext.bridge.TlsCertProvider
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
import kotlin.test.assertTrue
import kotlin.test.fail
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
 * The boundary end of #630: what the *real* `SuspendTlsSession.write` hands to
 * `SessionHandler`, with no stub session in between.
 *
 * #626 gave `SessionHandler.copyStreamToTls` a
 * `catch (e: TunnelError.UnhandledTlsState) { throw e }` clause ahead of its
 * broad catch, and proved with a stubbed session that the clause works. It was
 * still inert in production, because nothing on the write path ever threw that
 * type -- a genuine wrap defect arrived as a plain `IOException`, fell into the
 * broad `catch (_: Exception)`, and ended the copy loop as a clean EOF. Exactly
 * the outcome #616 eliminated on the read side.
 *
 * These tests close the gap by driving the production stack end to end: a
 * scripted `SSLEngine` behind a real [TlsAcceptor], reached through
 * `SessionHandler`'s own default `tlsAccept` (the `TlsCertProvider` hands back a
 * scripted [SSLContext], so there is no test seam in the TLS path at all).
 *
 * `:core:tunnel`'s test source set has `:core:bridge` on its classpath, which is
 * why the two halves can meet here.
 *
 * "Observable" means "propagates out of `handleStream`": this is a KMP jvm
 * target with no Android `Log` or `CrashReporter` on the classpath, and
 * `TunnelForegroundService.collectIncomingSessions` already wraps each
 * `handleStream` call in `catch (e: Exception) { Log.e(...); crashReporter
 * .logCaughtException(e) }`. Returning normally is therefore precisely "no error
 * log, no crash report".
 *
 * See [TlsAcceptorWrapStatusTest] for the per-status enumeration.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class WrapDefectReachesSessionHandlerTest {

    /** Stand-in for the local MCP server: accepts the bridge's loopback connection. */
    private val mcpServer = ServerSocket(0)

    @Volatile
    private var accepted: Socket? = null

    @AfterTest
    fun tearDown() {
        accepted?.close()
        mcpServer.close()
    }

    @Test
    fun `a wrap-side defect reaches the SessionHandler boundary`() = runBlocking {
        val handler = handlerWrapping(Status.BUFFER_OVERFLOW)
        val fed = feedTheWriteDirection()

        val thrown = runCatching {
            withTimeout(TIMEOUT_MS) { handler.handleStream(UnusedMuxStream()) }
        }.exceptionOrNull()

        withTimeout(TIMEOUT_MS) { fed.await() }

        if (thrown == null) {
            fail(
                "handleStream returned normally: a BUFFER_OVERFLOW wrap was swallowed " +
                    "back into a clean EOF, so #626's UnhandledTlsState clause on the " +
                    "write direction is still inert (#630)."
            )
        }
        assertTrue(
            thrown is TunnelError.UnhandledTlsState,
            "Expected TunnelError.UnhandledTlsState at the SessionHandler boundary, got $thrown"
        )

        coroutineContext.cancelChildren()
    }

    @Test
    fun `an ordinary CLOSED wrap still ends the session quietly`() = runBlocking {
        // A peer hanging up mid-response is routine on a bridge. Making it noisy
        // is worse than the bug being fixed: it trains whoever reads the log to
        // ignore it, which is the same failure one level up.
        val handler = handlerWrapping(Status.CLOSED)
        val fed = feedTheWriteDirection()

        withTimeout(TIMEOUT_MS) { handler.handleStream(UnusedMuxStream()) }

        withTimeout(TIMEOUT_MS) { fed.await() }

        coroutineContext.cancelChildren()
    }

    // ------------------------------------------------------------- helpers

    /**
     * Accepts the bridge's loopback connection and pushes a byte at it, so the
     * socket -> TLS direction actually reaches `tlsSession.write`. The socket is
     * held open (not `use`d) so the copy loop cannot see EOF before the write.
     */
    private fun CoroutineScope.feedTheWriteDirection(): CompletableDeferred<Unit> {
        val fed = CompletableDeferred<Unit>()
        launch(Dispatchers.IO) {
            val peer = mcpServer.accept()
            accepted = peer
            peer.getOutputStream().apply {
                write("response bytes".toByteArray())
                flush()
            }
            fed.complete(Unit)
        }
        return fed
    }

    private fun handlerWrapping(status: Status): SessionHandler {
        val sslSession = ScriptedSslEngine.nullSession()
        val engine = WrapStatusEngine(sslSession, status)
        return SessionHandler(
            certProvider = ScriptedCertProvider(ScriptedSslContext(engine)),
            mcpSessionFactory = LoopbackMcpSessionFactory(mcpServer.localPort)
            // tlsAccept deliberately left at its production default.
        )
    }

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

    /**
     * Handshake-free engine whose every `wrap` reports [status] and consumes
     * nothing. The read direction parks on the mux stream, so `wrap` is the only
     * engine call the session ever makes.
     */
    private class WrapStatusEngine(session: SSLSession, private val status: Status) :
        ScriptedSslEngine(session) {

        override fun getHandshakeStatus(): HandshakeStatus = HandshakeStatus.NOT_HANDSHAKING

        override fun scriptedUnwrap(src: ByteBuffer, dst: ByteBuffer): SSLEngineResult =
            error("wrap-status script: the read path parks on the mux stream")

        override fun scriptedWrap(src: ByteBuffer, dst: ByteBuffer): SSLEngineResult {
            check(wrapCalls <= MAX_NO_PROGRESS_CALLS) {
                "the write loop re-wrapped the same bytes $wrapCalls times on a $status " +
                    "status without classifying it (#630)"
            }
            return SSLEngineResult(status, HandshakeStatus.NOT_HANDSHAKING, 0, 0)
        }
    }

    /** The TLS -> socket direction parks here for the whole test. */
    private class UnusedMuxStream : MuxStream {
        override val id: UInt = 1u
        override val incoming: Flow<ByteArray> = emptyFlow()
        override val isClosed: Boolean = false

        override suspend fun send(data: ByteArray) = Unit

        override suspend fun close() = Unit

        override suspend fun read(): ByteArray = awaitCancellation()
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val MAX_NO_PROGRESS_CALLS = 20
    }
}
