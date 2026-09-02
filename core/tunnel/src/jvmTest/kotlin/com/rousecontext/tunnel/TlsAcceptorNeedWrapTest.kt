package com.rousecontext.tunnel

import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLEngineResult.Status
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Timeout

/**
 * Regression tests for #617: a post-handshake `unwrap` that reports
 * `NEED_WRAP` leaves an outgoing record inside the engine, and
 * `SuspendTlsSession.read` used to drop it.
 *
 * Both directions are pinned here, because the fix is a widening: the branch
 * that used to fire for `NEED_TASK` alone now fires for `NEED_WRAP` too, and a
 * widening that goes one member too far is worse than the gap it closes -- an
 * unprompted `wrap` mid-read emits a record the peer never asked for.
 *
 * - [read emits the record the engine asked for on post-handshake NEED_WRAP]
 *   is the gap.
 * - [read never wraps for a benign residual handshake status] and
 *   [read still runs delegated tasks and does not wrap for NEED_TASK] are the
 *   four no-ops and the one existing action, held still.
 *
 * Scripted engines rather than a real handshake, for the reason set out on
 * [ScriptedSslEngine]: the production loop runs verbatim with no seam. Each
 * engine counts consecutive no-progress calls so an unfixed loop reports a
 * specific failure in milliseconds instead of parking until the job budget
 * runs out -- the stall in #617 is cancellable but silent, so a plain timeout
 * would say nothing about why.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class TlsAcceptorNeedWrapTest {

    /**
     * The gap. A TLS 1.3 peer that sends `key_update{update_requested}` obliges
     * us to send a `key_update` back, and SunJSSE surfaces that obligation as a
     * post-handshake `unwrap` returning `handshakeStatus = NEED_WRAP` -- the
     * engine holds the response and waits for the application to `wrap` it out.
     *
     * `read` never wrapped, so the record stayed in the engine forever. This
     * asserts the record actually reaches the mux stream, not merely that a
     * `wrap` happened: the whole point is that the peer sees the response.
     */
    @Test
    fun `read emits the record the engine asked for on post-handshake NEED_WRAP`() = runBlocking {
        val session = ScriptedSslEngine.nullSession()
        val plaintext = "plaintext after the key update".toByteArray()
        val engine = NeedWrapEngine(session, plaintext)
        val acceptor = TlsAcceptor.create(ScriptedSslContext(engine))
        val stream = ScriptedMuxStream(listOf(ByteArray(32) { it.toByte() }))

        val tlsSession = withTimeout(TEST_TIMEOUT_MS) { acceptor.accept(stream) }
        val buf = ByteArray(256)
        val n = withTimeout(TEST_TIMEOUT_MS) { tlsSession.read(buf, 0, buf.size) }

        assertEquals(
            1,
            engine.wrapCalls,
            "read() never wrapped, so the record the engine asked for was never produced"
        )
        assertEquals(
            listOf(NeedWrapEngine.RESPONSE.toList()),
            stream.written.map { it.toList() },
            "the responding record never reached the mux stream"
        )
        assertContentEquals(plaintext, buf.copyOf(n), "read() did not go on to return plaintext")
        assertEquals(1, stream.reads, "read() pulled a DATA frame instead of emitting the record")
    }

    /**
     * The four no-ops, held still. `NOT_HANDSHAKING` and `FINISHED` are ordinary
     * post-handshake data, `NEED_UNWRAP` is what the loop's next iteration
     * already does, and `NEED_UNWRAP_AGAIN` is DTLS-oriented and never asks for
     * a record. None of them means "emit something", and emitting anyway would
     * put a record on the wire the peer never asked for.
     *
     * `ScriptedSslEngine.scriptedWrap` fails loudly by default, so a regression
     * that wraps unconditionally fails here with the engine's own message rather
     * than with a count mismatch.
     */
    @Test
    fun `read never wraps for a benign residual handshake status`() = runBlocking {
        val benign = listOf(
            HandshakeStatus.NOT_HANDSHAKING,
            HandshakeStatus.FINISHED,
            HandshakeStatus.NEED_UNWRAP,
            HandshakeStatus.NEED_UNWRAP_AGAIN
        )
        for (status in benign) {
            val session = ScriptedSslEngine.nullSession()
            val plaintext = "residual $status".toByteArray()
            val engine = ResidualStatusEngine(session, status, plaintext)
            val acceptor = TlsAcceptor.create(ScriptedSslContext(engine))
            val stream = ScriptedMuxStream(listOf(ByteArray(16) { it.toByte() }))

            val tlsSession = withTimeout(TEST_TIMEOUT_MS) { acceptor.accept(stream) }
            val buf = ByteArray(128)
            val n = withTimeout(TEST_TIMEOUT_MS) { tlsSession.read(buf, 0, buf.size) }

            assertContentEquals(plaintext, buf.copyOf(n), "read() lost plaintext on $status")
            assertEquals(0, engine.wrapCalls, "read() wrapped for $status, which asks for nothing")
            assertTrue(stream.written.isEmpty(), "read() put a record on the wire for $status")
        }
    }

    /**
     * The one action that already existed (#565 gap 3) still happens, and still
     * happens *alone*: a `NEED_TASK` runs the engine's deferred work and emits
     * nothing.
     */
    @Test
    fun `read still runs delegated tasks and does not wrap for NEED_TASK`() = runBlocking {
        val session = ScriptedSslEngine.nullSession()
        val plaintext = "after the delegated task".toByteArray()
        val engine = TaskThenPlaintextEngine(session, plaintext)
        val acceptor = TlsAcceptor.create(ScriptedSslContext(engine))
        val stream = ScriptedMuxStream(listOf(ByteArray(16) { it.toByte() }))

        val tlsSession = withTimeout(TEST_TIMEOUT_MS) { acceptor.accept(stream) }
        val buf = ByteArray(128)
        val n = withTimeout(TEST_TIMEOUT_MS) { tlsSession.read(buf, 0, buf.size) }

        assertTrue(engine.taskRan, "read() no longer runs the engine's delegated task")
        assertContentEquals(plaintext, buf.copyOf(n))
        assertEquals(0, engine.wrapCalls, "NEED_TASK must not emit a record")
        assertTrue(stream.written.isEmpty(), "NEED_TASK put a record on the wire")
    }

    // ---------------------------------------------------------------- engines

    /**
     * Handshake is already over (`NOT_HANDSHAKING`), so the pump returns without
     * unwrapping and the first `read` drives everything.
     *
     * The first post-handshake unwrap consumes ONE byte of the record and asks
     * for a `wrap`. Consuming one byte rather than the whole record keeps `netIn`
     * non-empty, so a loop that ignores `NEED_WRAP` re-unwraps rather than
     * reaching for another DATA frame: the failure it reports is "you never
     * emitted", not "you read too much". Only after the record has been wrapped
     * out does the engine produce plaintext.
     */
    private class NeedWrapEngine(session: SSLSession, private val plaintext: ByteArray) :
        ScriptedSslEngine(session) {

        private var status: HandshakeStatus = HandshakeStatus.NOT_HANDSHAKING
        private var noProgressCalls = 0

        @Volatile
        private var recordEmitted = false

        override fun getHandshakeStatus(): HandshakeStatus = status

        override fun scriptedUnwrap(src: ByteBuffer, dst: ByteBuffer): SSLEngineResult {
            if (!recordEmitted) {
                noProgressCalls++
                check(noProgressCalls <= MAX_NO_PROGRESS_CALLS) {
                    "read() unwrapped $noProgressCalls times without emitting the record the " +
                        "engine asked for: post-handshake NEED_WRAP is unhandled (#617)"
                }
                src.position(src.position() + 1)
                status = HandshakeStatus.NEED_WRAP
                return SSLEngineResult(Status.OK, HandshakeStatus.NEED_WRAP, 1, 0)
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

        override fun scriptedWrap(src: ByteBuffer, dst: ByteBuffer): SSLEngineResult {
            recordEmitted = true
            status = HandshakeStatus.NOT_HANDSHAKING
            dst.put(RESPONSE)
            return SSLEngineResult(Status.OK, HandshakeStatus.NOT_HANDSHAKING, 0, RESPONSE.size)
        }

        companion object {
            /** Stands in for the responding `key_update` record. */
            val RESPONSE = byteArrayOf(0x16, 0x03, 0x03, 0x00, 0x05)
        }
    }

    /**
     * Produces plaintext on the first post-handshake unwrap while reporting
     * [residual] as the handshake status. Inherits the loud `scriptedWrap`.
     */
    private class ResidualStatusEngine(
        session: SSLSession,
        private val residual: HandshakeStatus,
        private val plaintext: ByteArray
    ) : ScriptedSslEngine(session) {

        override fun getHandshakeStatus(): HandshakeStatus = HandshakeStatus.NOT_HANDSHAKING

        override fun scriptedUnwrap(src: ByteBuffer, dst: ByteBuffer): SSLEngineResult {
            val consumed = src.remaining()
            src.position(src.limit())
            dst.put(plaintext)
            return SSLEngineResult(Status.OK, residual, consumed, plaintext.size)
        }
    }

    /** First unwrap defers a task; plaintext only once that task has run. */
    private class TaskThenPlaintextEngine(session: SSLSession, private val plaintext: ByteArray) :
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
                    "read() never ran the delegated task the engine asked for"
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

    /** Hands out [chunks] one per `read()`; records reads and everything sent. */
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
                "read() parked on pullNetData(): it pulled a DATA frame instead of emitting " +
                    "the record the engine asked for (#617)"
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
         * Consecutive no-progress engine calls tolerated before the scripted
         * engine declares the production loop wedged. A correct loop makes one.
         */
        const val MAX_NO_PROGRESS_CALLS = 20

        const val TEST_TIMEOUT_MS = 15_000L
    }
}
