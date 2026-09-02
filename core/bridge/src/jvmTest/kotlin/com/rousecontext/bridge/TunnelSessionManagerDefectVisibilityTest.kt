package com.rousecontext.bridge

import com.rousecontext.tunnel.MuxStream
import com.rousecontext.tunnel.TunnelClient
import com.rousecontext.tunnel.TunnelError
import com.rousecontext.tunnel.TunnelState
import java.io.IOException
import javax.net.ssl.SSLContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Rule
import org.junit.rules.Timeout

/**
 * [TunnelSessionManager] wraps every `handleStream` call in a catch so that one
 * bad session cannot take the collector down. That catch has to make the same
 * three-way split the copy loops inside [SessionHandler] make (#616, #626,
 * #630, #642), and for the same reason:
 *
 *  - [kotlinx.coroutines.CancellationException] must propagate, or the manager
 *    stops being cancellable. On the JVM it is a
 *    [java.util.concurrent.CancellationException], which extends
 *    [IllegalStateException] and is therefore caught by `catch (_: Exception)`
 *    -- so clause ORDER is load-bearing, not decoration.
 *  - [TunnelError.UnhandledTlsState] must propagate, because it says our own
 *    TLS layer reached a state it has no handling for. `:core:bridge` is a KMP
 *    jvm target with no Android `Log` or `CrashReporter` on its classpath, so
 *    propagating out of the session coroutine into the caller's scope IS the
 *    reporting mechanism. A bare `catch (_: Exception)` here reproduces the
 *    clean-EOF outcome #615/#616/#626/#630 spent four issues eliminating.
 *  - everything else -- a peer hanging up, a socket reset -- must still be
 *    swallowed, because it is normal and frequent and must not take the
 *    collector down with it.
 *
 * Two of those three are pinned here, and the pairing is the point: a defect
 * test alone passes on a wrongly *widened* guard, because a guard that rethrows
 * a supertype rethrows the subtype too. Only
 * [an ordinary failure is still swallowed and collection continues] goes red on
 * `catch (e: Exception) { throw e }`. Both were verified by rewriting the guard
 * into each wrong shape and watching the suite go red -- reading the tests is
 * what missed this on #669.
 *
 * The cancellation clause is deliberately NOT claimed as pinned. Ablation says
 * so: deleting it leaves this whole suite green. In this position the session
 * body is the last thing in its coroutine, so swallowing a
 * `CancellationException` and returning normally leaves a job that was already
 * cancelled still cancelled -- there is no difference observable from outside
 * the manager, and manufacturing one would mean adding a seam to production
 * that exists only for the test. The clause stays anyway, because it costs
 * nothing, it matches [SessionHandler]'s guard exactly, and the moment anyone
 * adds a statement after the `try` it becomes load-bearing for real. What the
 * third test below does pin is a genuine property, just not that one.
 */
class TunnelSessionManagerDefectVisibilityTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(TEST_TIMEOUT_SECONDS)

    @Test
    fun `an unhandled TLS state escapes the manager into the caller's scope`() = runBlocking {
        val defect = TunnelError.UnhandledTlsState(UNWRAP_MESSAGE)
        val escaped = CompletableDeferred<Throwable>()
        val tunnel = FakeTunnelClient()

        val manager = TunnelSessionManager(
            tunnelClient = tunnel,
            sessionHandler = handlerThrowing { throw defect },
            scope = observedScope(escaped)
        )
        manager.start()
        withTimeout(TIMEOUT_MS) { tunnel.subscriberCount.first { it > 0 } }

        tunnel.emitSession(StubMuxStream(1u))

        val thrown = try {
            withTimeout(TIMEOUT_MS) { escaped.await() }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            fail(
                "The unhandled-TLS-state defect never left TunnelSessionManager: " +
                    "the broad catch swallowed it back into a clean EOF. This " +
                    "module cannot log, so propagation is the only report (#638)."
            )
        }

        assertTrue(
            thrown is TunnelError.UnhandledTlsState,
            "Expected TunnelError.UnhandledTlsState out of the manager, got $thrown"
        )
        assertEquals(UNWRAP_MESSAGE, thrown.message)

        manager.stop()
        coroutineContext.cancelChildren()
    }

    @Test
    fun `an ordinary failure is still swallowed and collection continues`() = runBlocking {
        // The widening detector, and the reason the other two tests are not
        // enough on their own. A guard mistakenly written as
        // `catch (e: Exception) { throw e }` still rethrows cancellation and
        // still rethrows UnhandledTlsState, so both of them stay green on it.
        // Only this test goes red -- verified by ablation, not by reading (#669).
        val escaped = CompletableDeferred<Throwable>()
        val firstStreamEntered = CompletableDeferred<Unit>()
        val secondStreamHandled = CompletableDeferred<Unit>()
        val tunnel = FakeTunnelClient()

        val handler = handlerThrowing { stream ->
            if (stream.id == 1u) {
                firstStreamEntered.complete(Unit)
                throw IOException("Connection reset by peer")
            }
            secondStreamHandled.complete(Unit)
            awaitCancellation()
        }
        val manager = TunnelSessionManager(
            tunnelClient = tunnel,
            sessionHandler = handler,
            scope = observedScope(escaped)
        )
        manager.start()
        withTimeout(TIMEOUT_MS) { tunnel.subscriberCount.first { it > 0 } }

        tunnel.emitSession(StubMuxStream(1u))
        withTimeout(TIMEOUT_MS) { firstStreamEntered.await() }

        // Ordered, not raced: the throw is the very next statement after the
        // signal above, so if this guard rethrows ordinary exceptions the
        // failure reaches the scope handler well inside SETTLE_MS. Waiting the
        // full window and finding nothing is what makes the null meaningful.
        val leaked = withTimeoutOrNull(SETTLE_MS) { escaped.await() }
        assertNull(
            leaked,
            "An ordinary IOException escaped the manager. Routine peer " +
                "disconnects must stay quiet, or the defect channel this guard " +
                "exists to protect fills with noise (#616)."
        )

        // ...and the collector itself must have survived the failed session.
        tunnel.emitSession(StubMuxStream(2u))
        try {
            withTimeout(TIMEOUT_MS) { secondStreamHandled.await() }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            fail(
                "The manager stopped collecting after an ordinary session " +
                    "failure. One bad session must not take the collector down."
            )
        }

        manager.stop()
        coroutineContext.cancelChildren()
    }

    @Test
    fun `cancelling the manager's scope cancels a running session`() = runBlocking {
        // Structured concurrency, not guard shape: sessions must be children of
        // the caller's scope, so cancelling it unwinds a session that is parked
        // mid-stream. This goes red if someone reaches for NonCancellable or
        // launches sessions on a scope the caller does not own.
        //
        // It does NOT detect a dropped cancellation clause -- see the class
        // KDoc. Do not read it as covering that.
        val escaped = CompletableDeferred<Throwable>()
        val sessionEntered = CompletableDeferred<Unit>()
        val sessionCancelled = CompletableDeferred<Unit>()
        val tunnel = FakeTunnelClient()

        val handler = handlerThrowing {
            sessionEntered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                sessionCancelled.complete(Unit)
            }
        }
        val scopeJob = SupervisorJob(coroutineContext[Job])
        val manager = TunnelSessionManager(
            tunnelClient = tunnel,
            sessionHandler = handler,
            scope = observedScope(escaped, scopeJob)
        )
        manager.start()
        withTimeout(TIMEOUT_MS) { tunnel.subscriberCount.first { it > 0 } }

        tunnel.emitSession(StubMuxStream(1u))
        withTimeout(TIMEOUT_MS) { sessionEntered.await() }

        scopeJob.cancel()

        try {
            withTimeout(TIMEOUT_MS) { sessionCancelled.await() }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            fail(
                "Cancelling the manager's scope did not cancel the running " +
                    "session: the guard swallowed CancellationException."
            )
        }
        assertTrue(scopeJob.isCancelled, "manager scope should be cancelled")

        coroutineContext.cancelChildren()
    }

    // -- helpers --

    /**
     * A scope whose uncaught child failures land in [sink] instead of the
     * default handler. This is not a test-only seam bolted onto the manager:
     * the manager already reports by letting the exception leave its session
     * coroutine, and in production the caller's scope is what observes it. The
     * test just supplies a scope that records rather than crashes.
     */
    private fun CoroutineScope.observedScope(
        sink: CompletableDeferred<Throwable>,
        job: Job = SupervisorJob(coroutineContext[Job])
    ): CoroutineScope = CoroutineScope(
        coroutineContext + job + CoroutineExceptionHandler { _, e -> sink.complete(e) }
    )

    /**
     * A real [SessionHandler] whose TLS accept step runs [onStream] instead of
     * handshaking. `handleStream` calls `tlsAccept` before its own try block, so
     * whatever [onStream] throws is exactly what leaves `handleStream`.
     */
    private fun handlerThrowing(onStream: suspend (MuxStream) -> Nothing): SessionHandler =
        SessionHandler(
            certProvider = DefaultSslContextProvider(),
            mcpSessionFactory = UnusedMcpSessionFactory(),
            tlsAccept = { _, stream -> onStream(stream) }
        )

    private class DefaultSslContextProvider : TlsCertProvider {
        override suspend fun serverSslContext(): SSLContext = SSLContext.getDefault()
    }

    private class UnusedMcpSessionFactory : McpSessionFactory {
        override suspend fun create(): McpSessionHandle =
            error("tlsAccept throws first; no MCP session should ever be created")
    }

    private class StubMuxStream(override val id: UInt) : MuxStream {
        override val incoming: Flow<ByteArray> = MutableSharedFlow()
        override val isClosed: Boolean = false

        override suspend fun send(data: ByteArray) = Unit

        override suspend fun close() = Unit

        override suspend fun read(): ByteArray = awaitCancellation()
    }

    private class FakeTunnelClient : TunnelClient {
        private val _incomingSessions = MutableSharedFlow<MuxStream>(
            extraBufferCapacity = 0,
            replay = 0
        )

        override val state: StateFlow<TunnelState> = MutableStateFlow(TunnelState.CONNECTED)
        override val errors: SharedFlow<TunnelError> = MutableSharedFlow()
        override val incomingSessions: Flow<MuxStream> = _incomingSessions

        override suspend fun connect(url: String) = Unit

        override suspend fun disconnect() = Unit

        override suspend fun sendFcmToken(token: String) = Unit

        override suspend fun sendPushEndpoint(kind: String, value: String) = Unit

        override suspend fun healthCheck(timeout: kotlin.time.Duration): Boolean = true

        suspend fun emitSession(stream: MuxStream) = _incomingSessions.emit(stream)

        val subscriberCount: StateFlow<Int> get() = _incomingSessions.subscriptionCount
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L

        /** How long "nothing escaped" is allowed to take before it counts. */
        const val SETTLE_MS = 2_000L
        const val UNWRAP_MESSAGE = "Unhandled TLS unwrap status: FAKE"
    }
}
