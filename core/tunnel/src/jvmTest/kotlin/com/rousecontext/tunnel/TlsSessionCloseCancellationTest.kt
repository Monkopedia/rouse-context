package com.rousecontext.tunnel

import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Timeout

/**
 * `TlsSession.close()` is cleanup, and cleanup runs on the way out of a
 * cancelled scope or it does not run at all (#649).
 *
 * `SessionHandler.bridgeToMcpServer` already expresses this intent with
 * `withContext(NonCancellable)` around its socket close, and
 * `.claude/rules/coroutines.md` prescribes that form. `close()` used to express
 * it by swallowing instead -- a bare `catch (_: Exception)` around a **suspend**
 * `stream.close()`. The two are not equivalent:
 *
 *  - swallowing loses the cancellation *and* skips the work, because on the JVM
 *    a `CancellationException` raised at `stream.close()`'s first suspension
 *    point is an `Exception` and lands in the broad catch before the CLOSE
 *    frame is ever sent;
 *  - `NonCancellable` completes the work and leaves the cancellation intact for
 *    the caller.
 *
 * #647 had just removed the same swallow from `accept`, which is what made
 * `close()` still doing it the inconsistency in this file most likely to be
 * copied.
 *
 * The mux stream here suspends inside `close()` before recording the close, the
 * way the real one does -- `MuxStream.close` sends a CLOSE frame over the
 * WebSocket. Without that suspension point nothing distinguishes the two forms.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class TlsSessionCloseCancellationTest {

    @Test
    fun `close finishes the mux close even when the caller is already cancelled`() = runBlocking {
        val stream = SuspendingCloseMuxStream()
        val session = sessionOver(stream)

        val parked = CompletableDeferred<Unit>()
        val closeFailure = CompletableDeferred<Throwable?>()

        val job = launch(Dispatchers.Default) {
            try {
                parked.complete(Unit)
                awaitCancellation()
            } finally {
                closeFailure.complete(runCatching { session.close() }.exceptionOrNull())
            }
        }

        withTimeout(TIMEOUT_MS) { parked.await() }
        job.cancel()
        job.join()

        assertTrue(
            stream.closed,
            "close() must finish the underlying mux close even though the caller was " +
                "being cancelled: swallowing the CancellationException from the suspend " +
                "stream.close() skips the CLOSE frame entirely (#649)"
        )

        val failure = withTimeout(TIMEOUT_MS) { closeFailure.await() }
        assertTrue(
            failure == null || failure is CancellationException,
            "close() must not turn a cancelled caller into some other failure, got: $failure"
        )
        assertTrue(job.isCancelled, "the caller's cancellation must survive the cleanup")
    }

    // ------------------------------------------------------------- helpers

    private suspend fun sessionOver(stream: MuxStream): TlsAcceptor.TlsSession =
        TlsAcceptor.create(ScriptedSslContext(IdleEngine(ScriptedSslEngine.nullSession())))
            .accept(stream)

    /** Reports `NOT_HANDSHAKING`, so `accept` hands back a session without any I/O. */
    private class IdleEngine(session: SSLSession) : ScriptedSslEngine(session) {
        override fun getHandshakeStatus(): HandshakeStatus = HandshakeStatus.NOT_HANDSHAKING

        override fun scriptedUnwrap(src: ByteBuffer, dst: ByteBuffer): SSLEngineResult =
            error("idle script: no unwrap expected")
    }

    /**
     * Suspends before recording the close, like the real [MuxStream.close]
     * sending its CLOSE frame. A `yield()` is a cancellable suspension point, so
     * under a cancelled caller it throws unless the close runs `NonCancellable`.
     */
    private class SuspendingCloseMuxStream : MuxStream {
        override val id: UInt = 1u
        override val incoming: Flow<ByteArray> = emptyFlow()
        override val isClosed: Boolean get() = closed

        @Volatile
        var closed: Boolean = false
            private set

        override suspend fun send(data: ByteArray) = Unit

        override suspend fun close() {
            yield()
            closed = true
        }

        override suspend fun read(): ByteArray = awaitCancellation()
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
