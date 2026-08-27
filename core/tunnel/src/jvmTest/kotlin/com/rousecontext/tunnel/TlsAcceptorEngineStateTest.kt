package com.rousecontext.tunnel

import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLEngineResult.Status
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Timeout

/**
 * Regression tests for the three unhandled `SSLEngine` states in [TlsAcceptor]
 * whose fallback silently loops or exits quietly (#565).
 *
 * Same family as #558/#563: an engine result the loop does not handle, where the
 * fallback either re-runs the same call forever (no suspension point, so not even
 * cancellable) or `break`s out of a handshake that was still progressing and lets
 * the caller mistake that for success.
 *
 * Each test scripts the engine directly -- see [ScriptedSslEngine] for why a fake
 * engine is the only way to supply these states -- and each is written to report a
 * *fast, specific* red rather than a hang: the scripted engines count consecutive
 * no-progress calls and throw with a diagnostic message once the loop has clearly
 * wedged. The class still carries a `SEPARATE_THREAD` ceiling for the same reason
 * [TlsAcceptorSplitRecordTest] does -- an uncancellable spin cannot be interrupted
 * by `withTimeout`, and a wedged `runBlocking` writes no JUnit XML at all.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class TlsAcceptorEngineStateTest {

    /**
     * Gap 1: `BUFFER_OVERFLOW` during the handshake pump.
     *
     * The pump reads `result.status` only to set `needMoreNetData` from
     * `BUFFER_UNDERFLOW`. On `BUFFER_OVERFLOW` nothing grows `appIn` and nothing
     * consumes `netIn`, so the loop re-unwraps the same bytes forever.
     * `SuspendTlsSession.read` already grows and retries; the two must agree.
     */
    @Test
    fun `handshake pump grows appIn and retries on BUFFER_OVERFLOW`() = runBlocking {
        val session = ScriptedSslEngine.nullSession()
        // Demand more room than the pump's initial appIn, so it must grow twice.
        val engine = OverflowHandshakeEngine(session, session.applicationBufferSize * 3)
        val acceptor = TlsAcceptor.create(ScriptedSslContext(engine))
        val stream = ScriptedMuxStream(listOf(ByteArray(64) { it.toByte() }))

        withTimeout(TEST_TIMEOUT_MS) { acceptor.accept(stream) }

        assertTrue(
            engine.grewToAtLeastRequired,
            "handshake pump never grew appIn past BUFFER_OVERFLOW"
        )
        assertEquals(1, stream.reads, "pump must not pull more net data on BUFFER_OVERFLOW")
    }

    /**
     * Gap 2: `NEED_UNWRAP_AGAIN` falls into `else -> break`.
     *
     * It means the engine has buffered data to process *without* another network
     * read. Breaking abandons a handshake that was still progressing, and the
     * caller cannot tell that apart from a completed one -- `accept` returns a
     * session either way, which is why this test asserts on the engine's call
     * count rather than on `accept` throwing.
     */
    @Test
    fun `handshake pump unwraps again on NEED_UNWRAP_AGAIN without reading more`() = runBlocking {
        val session = ScriptedSslEngine.nullSession()
        val engine = UnwrapAgainHandshakeEngine(session)
        val acceptor = TlsAcceptor.create(ScriptedSslContext(engine))
        val stream = ScriptedMuxStream(listOf(ByteArray(48) { it.toByte() }))

        withTimeout(TEST_TIMEOUT_MS) { acceptor.accept(stream) }

        assertEquals(
            2,
            engine.unwrapCalls,
            "NEED_UNWRAP_AGAIN must drive a second unwrap, not break the handshake loop"
        )
        assertTrue(engine.reachedFinished, "handshake never reached FINISHED")
        assertEquals(
            1,
            stream.reads,
            "NEED_UNWRAP_AGAIN must reuse buffered data, not pull another DATA frame"
        )
        assertFalse(engine.sawNetDataOnSecondUnwrap, "second unwrap was handed fresh net data")
    }

    /**
     * Gap 3: `SuspendTlsSession.read` never runs delegated tasks.
     *
     * The handshake pump calls `runDelegatedTasks` on `NEED_TASK`; `read` had no
     * such branch, so an engine that defers work after the handshake can never
     * make progress -- `read` re-unwraps the same bytes forever.
     */
    @Test
    fun `session read runs delegated tasks when the engine asks for one`() = runBlocking {
        val session = ScriptedSslEngine.nullSession()
        val plaintext = "delegated task then plaintext".toByteArray()
        val engine = PostHandshakeTaskEngine(session, plaintext)
        val acceptor = TlsAcceptor.create(ScriptedSslContext(engine))
        val stream = ScriptedMuxStream(listOf(ByteArray(32) { it.toByte() }))

        val tlsSession = withTimeout(TEST_TIMEOUT_MS) { acceptor.accept(stream) }

        val buf = ByteArray(256)
        val n = withTimeout(TEST_TIMEOUT_MS) { tlsSession.read(buf, 0, buf.size) }

        assertTrue(engine.taskRan, "read() never ran the engine's delegated task")
        assertEquals(String(plaintext), String(buf, 0, n))
    }

    // ---------------------------------------------------------------- engines

    /**
     * Reports `BUFFER_OVERFLOW` (consuming nothing) until the destination buffer
     * has [requiredRoom] bytes free, then consumes the whole record and finishes.
     *
     * Throws instead of overflowing forever so an unfixed loop reports a specific
     * failure in milliseconds rather than burning the job budget on a spin.
     */
    private class OverflowHandshakeEngine(session: SSLSession, private val requiredRoom: Int) :
        ScriptedSslEngine(session) {

        private var status: HandshakeStatus = HandshakeStatus.NEED_UNWRAP
        private var consecutiveOverflows = 0
        var grewToAtLeastRequired = false
            private set

        override fun getHandshakeStatus(): HandshakeStatus = status

        override fun scriptedUnwrap(src: ByteBuffer, dst: ByteBuffer): SSLEngineResult {
            if (dst.remaining() < requiredRoom) {
                consecutiveOverflows++
                check(consecutiveOverflows <= MAX_NO_PROGRESS_CALLS) {
                    "handshake pump re-unwrapped the same bytes $consecutiveOverflows times " +
                        "without growing appIn: BUFFER_OVERFLOW is unhandled (#565 gap 1)"
                }
                return SSLEngineResult(Status.BUFFER_OVERFLOW, HandshakeStatus.NEED_UNWRAP, 0, 0)
            }
            grewToAtLeastRequired = true
            val consumed = src.remaining()
            src.position(src.limit())
            status = HandshakeStatus.FINISHED
            return SSLEngineResult(Status.OK, HandshakeStatus.FINISHED, consumed, 0)
        }
    }

    /**
     * First unwrap consumes the record and reports `NEED_UNWRAP_AGAIN`; the second
     * must arrive with no fresh network data and finishes the handshake.
     */
    private class UnwrapAgainHandshakeEngine(session: SSLSession) : ScriptedSslEngine(session) {

        private var status: HandshakeStatus = HandshakeStatus.NEED_UNWRAP
        var reachedFinished = false
            private set
        var sawNetDataOnSecondUnwrap = false
            private set

        override fun getHandshakeStatus(): HandshakeStatus = status

        override fun scriptedUnwrap(src: ByteBuffer, dst: ByteBuffer): SSLEngineResult {
            if (status == HandshakeStatus.NEED_UNWRAP) {
                val consumed = src.remaining()
                src.position(src.limit())
                status = HandshakeStatus.NEED_UNWRAP_AGAIN
                return SSLEngineResult(Status.OK, HandshakeStatus.NEED_UNWRAP_AGAIN, consumed, 0)
            }
            sawNetDataOnSecondUnwrap = src.hasRemaining()
            status = HandshakeStatus.FINISHED
            reachedFinished = true
            return SSLEngineResult(Status.OK, HandshakeStatus.FINISHED, 0, 0)
        }
    }

    /**
     * Completes the handshake immediately (`NOT_HANDSHAKING`), then makes the first
     * post-handshake unwrap report `NEED_TASK` while consuming nothing. Only once
     * the delegated task has been run does it produce plaintext.
     */
    private class PostHandshakeTaskEngine(session: SSLSession, private val plaintext: ByteArray) :
        ScriptedSslEngine(session) {

        private var pendingTask: Runnable? = Runnable { taskRan = true }
        private var noProgressCalls = 0

        @Volatile
        var taskRan = false
            private set

        override fun getHandshakeStatus(): HandshakeStatus = HandshakeStatus.NOT_HANDSHAKING

        override fun getDelegatedTask(): Runnable? = pendingTask.also { pendingTask = null }

        override fun scriptedUnwrap(src: ByteBuffer, dst: ByteBuffer): SSLEngineResult {
            if (!taskRan) {
                noProgressCalls++
                check(noProgressCalls <= MAX_NO_PROGRESS_CALLS) {
                    "read() re-unwrapped the same bytes $noProgressCalls times without running " +
                        "the delegated task the engine asked for (#565 gap 3)"
                }
                return SSLEngineResult(Status.OK, HandshakeStatus.NEED_TASK, 0, 0)
            }
            val consumed = src.remaining()
            src.position(src.limit())
            dst.put(plaintext)
            return SSLEngineResult(
                Status.OK,
                HandshakeStatus.NOT_HANDSHAKING,
                consumed,
                plaintext.size
            )
        }
    }

    // ---------------------------------------------------------------- streams

    /** Hands out [chunks] one per `read()`; records how many reads happened. */
    private class ScriptedMuxStream(private val chunks: List<ByteArray>) : MuxStream {
        override val id: UInt get() = 1u
        override val incoming: Flow<ByteArray> get() = emptyFlow()
        override var isClosed: Boolean = false
            private set

        var reads: Int = 0
            private set

        val written = mutableListOf<ByteArray>()

        override suspend fun read(): ByteArray {
            val index = reads++
            check(index < chunks.size) {
                "unexpected extra stream.read(): the loop pulled a DATA frame it should " +
                    "have had buffered already"
            }
            return chunks[index]
        }

        override suspend fun send(data: ByteArray) {
            written += data
        }

        override suspend fun close() {
            isClosed = true
        }
    }

    private companion object {
        /**
         * How many consecutive no-progress engine calls the scripted engines tolerate
         * before declaring the production loop wedged. A correct loop makes at most a
         * handful (each `BUFFER_OVERFLOW` doubles `appIn`, so growth is geometric).
         */
        const val MAX_NO_PROGRESS_CALLS = 20

        const val TEST_TIMEOUT_MS = 15_000L
    }
}
