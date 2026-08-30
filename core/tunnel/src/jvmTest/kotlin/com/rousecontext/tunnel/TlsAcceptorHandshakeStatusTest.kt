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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Timeout

/**
 * How `TlsAcceptor.pumpHandshake` classifies each `SSLEngineResult.Status` the
 * engine can report from the handshake's `unwrap` and `wrap` (#618).
 *
 * ## The enumeration
 *
 * `javax.net.ssl.SSLEngineResult.Status` is a closed four-member enum -- verified
 * with `javap javax.net.ssl.SSLEngineResult$Status` against the JDK this module
 * builds on (`openjdk 21.0.12.1+1`), not from memory:
 *
 * ```
 * BUFFER_UNDERFLOW  BUFFER_OVERFLOW  OK  CLOSED
 * ```
 *
 * | status             | handshake `unwrap`        | handshake `wrap`     |
 * |--------------------|---------------------------|----------------------|
 * | `OK`               | ordinary -- continue      | ordinary -- continue |
 * | `BUFFER_UNDERFLOW` | ordinary -- pull a frame  | DEFECT               |
 * | `BUFFER_OVERFLOW`  | ordinary -- grow `appIn`  | DEFECT               |
 * | `CLOSED`           | handshake failure         | handshake failure    |
 *
 * ## Why the two directions disagree about the buffer statuses
 *
 * On `unwrap` the destination is `appIn`, sized from `applicationBufferSize`
 * (a hint), and the source is `netIn`, filled from mux DATA frames that carry no
 * relation to TLS record boundaries. So `BUFFER_UNDERFLOW` is the everyday
 * split-record case (#558) and `BUFFER_OVERFLOW` is recovered by growing --
 * both ordinary, and both already handled here.
 *
 * On `wrap` the destination is `netOut`: private to the pump, allocated at
 * `session.packetBufferSize`, and `clear()`ed before every call, so it always
 * offers the engine its own advertised maximum for one record. Overflow into
 * that means the engine broke a bound this layer owns. And `BUFFER_UNDERFLOW`
 * is an `unwrap` concept -- "the source holds less than a whole record" -- while
 * the pump's `appOut` is deliberately zero-capacity, because a handshake `wrap`
 * emits records from engine state and takes no application input at all.
 * Reporting underflow from it is a contract violation. Same enum, different
 * invariant: the asymmetry is argued, not inherited.
 *
 * ## Why `CLOSED` is not filed as a defect
 *
 * A peer sending `close_notify` mid-handshake is peer behaviour, not a bug in
 * this layer, so it must not wear [TunnelError.UnhandledTlsState] -- that type
 * exists so `SessionHandler` can stay quiet about disconnects while still
 * surfacing our own defects (#616, #630), and filing routine peer behaviour
 * under it trains whoever reads those reports to ignore the type. It still has
 * to fail `accept`: the pre-fix behaviour was to exit the pump normally and hand
 * back a `SuspendTlsSession` over an already-closed engine, whose first `read`
 * degrades to a clean EOF -- a half-open session indistinguishable from a good
 * one, which is how #558 survived four filings.
 * [TunnelError.TlsHandshakeFailed] is the type `accept` already documents and
 * the one #615 chose for the residual `else` in this same loop.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class TlsAcceptorHandshakeStatusTest {

    // -------------------------------------------------- unwrap: peer close

    @Test
    fun `handshake unwrap reporting CLOSED fails the accept instead of returning a session`() =
        runBlocking {
            val session = ScriptedSslEngine.nullSession()
            val engine = ClosingUnwrapEngine(session)
            val acceptor = TlsAcceptor.create(ScriptedSslContext(engine))
            val stream = ScriptedMuxStream(listOf(ByteArray(64) { it.toByte() }))

            val thrown = assertFailsWith<TunnelError.TlsHandshakeFailed> {
                withTimeout(TEST_TIMEOUT_MS) { acceptor.accept(stream) }
            }

            assertTrue(
                thrown.message.orEmpty().contains("CLOSED"),
                "the throw must name the status it stopped on, got: ${thrown.message}"
            )
        }

    @Test
    fun `a mid-handshake peer close is not filed as a defect in this layer`() = runBlocking {
        val session = ScriptedSslEngine.nullSession()
        val engine = ClosingUnwrapEngine(session)
        val acceptor = TlsAcceptor.create(ScriptedSslContext(engine))
        val stream = ScriptedMuxStream(listOf(ByteArray(64) { it.toByte() }))

        val thrown = assertFailsWith<TunnelError> {
            withTimeout(TEST_TIMEOUT_MS) { acceptor.accept(stream) }
        }

        assertFalse(
            TunnelError.UnhandledTlsState::class.java.isInstance(thrown),
            "a peer's close_notify is not a defect in this layer, got: $thrown"
        )
    }

    // ------------------------------------------- unwrap: ordinary controls

    @Test
    fun `handshake unwrap reporting OK completes the handshake`() = runBlocking {
        val session = ScriptedSslEngine.nullSession()
        val engine = OkUnwrapEngine(session)
        val acceptor = TlsAcceptor.create(ScriptedSslContext(engine))
        val stream = ScriptedMuxStream(listOf(ByteArray(64) { it.toByte() }))

        val tlsSession = withTimeout(TEST_TIMEOUT_MS) { acceptor.accept(stream) }

        assertNotNull(tlsSession, "an OK unwrap must finish the handshake, not fail it")
        assertEquals(1, engine.unwrapCalls)
    }

    @Test
    fun `handshake unwrap reporting BUFFER_UNDERFLOW pulls another frame`() = runBlocking {
        // The everyday split-record case (#558): one TLS record arriving across
        // two mux DATA frames. It must stay ordinary on this path.
        val session = ScriptedSslEngine.nullSession()
        val engine = UnderflowThenOkUnwrapEngine(session)
        val acceptor = TlsAcceptor.create(ScriptedSslContext(engine))
        val stream = ScriptedMuxStream(
            listOf(ByteArray(32) { it.toByte() }, ByteArray(32) { it.toByte() })
        )

        withTimeout(TEST_TIMEOUT_MS) { acceptor.accept(stream) }

        assertEquals(2, stream.reads, "BUFFER_UNDERFLOW must force one more DATA frame")
        assertEquals(2, engine.unwrapCalls)
    }

    // -------------------------------------------------- wrap: the defects

    @Test
    fun `handshake wrap reporting BUFFER_OVERFLOW is a defect`() = runBlocking {
        val thrown = assertFailsWith<TunnelError.UnhandledTlsState> {
            withTimeout(TEST_TIMEOUT_MS) { acceptWrapping(Status.BUFFER_OVERFLOW) }
        }

        assertTrue(
            thrown.message.orEmpty().contains("BUFFER_OVERFLOW"),
            "the throw must name the status it could not handle, got: ${thrown.message}"
        )
    }

    @Test
    fun `handshake wrap reporting BUFFER_UNDERFLOW is a defect`() = runBlocking {
        val thrown = assertFailsWith<TunnelError.UnhandledTlsState> {
            withTimeout(TEST_TIMEOUT_MS) { acceptWrapping(Status.BUFFER_UNDERFLOW) }
        }

        assertTrue(
            thrown.message.orEmpty().contains("BUFFER_UNDERFLOW"),
            "the throw must name the status it could not handle, got: ${thrown.message}"
        )
    }

    // --------------------------------------------------- wrap: peer close

    @Test
    fun `handshake wrap reporting CLOSED fails the accept, and not as a defect`() = runBlocking {
        val stream = ScriptedMuxStream(emptyList())
        val record = "close_notify".toByteArray()

        val thrown = assertFailsWith<TunnelError> {
            withTimeout(TEST_TIMEOUT_MS) {
                acceptWrapping(Status.CLOSED, produce = record, stream = stream)
            }
        }

        assertFalse(
            TunnelError.UnhandledTlsState::class.java.isInstance(thrown),
            "an engine closing mid-handshake is not a defect in this layer, got: $thrown"
        )
        assertTrue(
            thrown.message.orEmpty().contains("CLOSED"),
            "the throw must name the status it stopped on, got: ${thrown.message}"
        )
        assertEquals(
            1,
            stream.sent.size,
            "the record the engine did produce must still reach the peer before failing"
        )
        assertContentEquals(record, stream.sent.single())
    }

    // --------------------------------------------------- wrap: ordinary

    @Test
    fun `handshake wrap reporting OK emits the record and completes`() = runBlocking {
        val record = "server_hello".toByteArray()
        val stream = ScriptedMuxStream(emptyList())

        val tlsSession = withTimeout(TEST_TIMEOUT_MS) {
            acceptWrapping(Status.OK, produce = record, stream = stream)
        }

        assertNotNull(tlsSession, "an OK wrap must finish the handshake, not fail it")
        assertEquals(1, stream.sent.size)
        assertContentEquals(record, stream.sent.single())
    }

    // -------------------------------------------------------- cancellation

    @Test
    fun `cancellation during the handshake write is not reclassified as a defect`() = runBlocking {
        // The engine has a defect status queued AND the transport write is
        // cancelled. Cancellation wins: it must never be laundered into a
        // tunnel defect. This file has already produced one uncancellable spin
        // (#563), so the rule gets a test rather than a comment.
        val stream = ScriptedMuxStream(
            emptyList(),
            onSend = { throw CancellationException("cancelled") }
        )

        // No `withTimeout`: a CancellationException of our own would be
        // indistinguishable from the timeout's. The class @Timeout and the
        // script's no-progress ceiling are the backstops.
        val thrown = assertFailsWith<Throwable> {
            acceptWrapping(
                Status.BUFFER_OVERFLOW,
                produce = "ciphertext".toByteArray(),
                stream = stream
            )
        }

        assertFalse(
            TunnelError.UnhandledTlsState::class.java.isInstance(thrown),
            "cancellation must not be laundered into a tunnel defect, got: $thrown"
        )
        assertTrue(
            generateSequence<Throwable>(thrown) { it.cause }.any { it is CancellationException },
            "the cancellation must survive somewhere in the chain, got: $thrown"
        )
    }

    // ------------------------------------------------------------- helpers

    private suspend fun acceptWrapping(
        status: Status,
        produce: ByteArray = ByteArray(0),
        stream: ScriptedMuxStream = ScriptedMuxStream(emptyList())
    ): TlsAcceptor.TlsSession {
        val sslSession = ScriptedSslEngine.nullSession()
        val engine = HandshakeWrapStatusEngine(sslSession, status, produce)
        return TlsAcceptor.create(ScriptedSslContext(engine)).accept(stream)
    }

    // ------------------------------------------------------------- engines

    /**
     * The peer sends `close_notify` mid-handshake: `unwrap` consumes the record
     * and reports `CLOSED`, and the engine then says `NOT_HANDSHAKING` -- which
     * is precisely the trace that let the pre-fix pump exit its `while` guard
     * normally and hand back a session over a closed engine.
     */
    private class ClosingUnwrapEngine(session: SSLSession) : ScriptedSslEngine(session) {
        private var hs: HandshakeStatus = HandshakeStatus.NEED_UNWRAP

        override fun getHandshakeStatus(): HandshakeStatus = hs

        override fun scriptedUnwrap(src: ByteBuffer, dst: ByteBuffer): SSLEngineResult {
            val consumed = src.remaining()
            src.position(src.limit())
            hs = HandshakeStatus.NOT_HANDSHAKING
            return SSLEngineResult(Status.CLOSED, HandshakeStatus.NOT_HANDSHAKING, consumed, 0)
        }
    }

    /** One clean unwrap finishes the handshake. */
    private class OkUnwrapEngine(session: SSLSession) : ScriptedSslEngine(session) {
        private var hs: HandshakeStatus = HandshakeStatus.NEED_UNWRAP

        override fun getHandshakeStatus(): HandshakeStatus = hs

        override fun scriptedUnwrap(src: ByteBuffer, dst: ByteBuffer): SSLEngineResult {
            val consumed = src.remaining()
            src.position(src.limit())
            hs = HandshakeStatus.FINISHED
            return SSLEngineResult(Status.OK, HandshakeStatus.FINISHED, consumed, 0)
        }
    }

    /**
     * First unwrap underflows on a partial record without consuming anything;
     * the pump must pull one more DATA frame before the second unwrap succeeds.
     */
    private class UnderflowThenOkUnwrapEngine(session: SSLSession) : ScriptedSslEngine(session) {
        private var hs: HandshakeStatus = HandshakeStatus.NEED_UNWRAP

        override fun getHandshakeStatus(): HandshakeStatus = hs

        override fun scriptedUnwrap(src: ByteBuffer, dst: ByteBuffer): SSLEngineResult {
            check(unwrapCalls <= MAX_NO_PROGRESS_CALLS) {
                "handshake pump re-unwrapped the same partial record $unwrapCalls times"
            }
            if (unwrapCalls == 1) {
                return SSLEngineResult(
                    Status.BUFFER_UNDERFLOW,
                    HandshakeStatus.NEED_UNWRAP,
                    0,
                    0
                )
            }
            val consumed = src.remaining()
            src.position(src.limit())
            hs = HandshakeStatus.FINISHED
            return SSLEngineResult(Status.OK, HandshakeStatus.FINISHED, consumed, 0)
        }
    }

    /**
     * Drives the pump straight into `NEED_WRAP` and reports [status] from the
     * wrap, having produced [produce] into the destination.
     *
     * The follow-on handshake status is what makes an unclassified status
     * observable: `OK` finishes; `CLOSED` reports `NOT_HANDSHAKING`, which is
     * the real engine's behaviour and let the pre-fix pump return a dead
     * session; the two buffer statuses stay `NEED_WRAP`, so a pump that does
     * not classify them re-wraps forever (the reviewer's 100%-CPU trace) and
     * trips the ceiling below with a specific message instead of hanging.
     */
    private class HandshakeWrapStatusEngine(
        session: SSLSession,
        private val status: Status,
        private val produce: ByteArray
    ) : ScriptedSslEngine(session) {

        override fun getHandshakeStatus(): HandshakeStatus = HandshakeStatus.NEED_WRAP

        override fun scriptedUnwrap(src: ByteBuffer, dst: ByteBuffer): SSLEngineResult =
            error("handshake wrap-status script: the unwrap path is not exercised here")

        override fun scriptedWrap(src: ByteBuffer, dst: ByteBuffer): SSLEngineResult {
            check(wrapCalls <= MAX_NO_PROGRESS_CALLS) {
                "the handshake pump re-wrapped $wrapCalls times on a $status status: " +
                    "it neither classified the status nor made progress (#618)"
            }
            dst.put(produce)
            val next = when (status) {
                Status.OK -> HandshakeStatus.FINISHED
                Status.CLOSED -> HandshakeStatus.NOT_HANDSHAKING
                Status.BUFFER_OVERFLOW, Status.BUFFER_UNDERFLOW -> HandshakeStatus.NEED_WRAP
            }
            return SSLEngineResult(status, next, 0, produce.size)
        }
    }

    // ------------------------------------------------------------- streams

    /**
     * Hands out [chunks] one per `read()` and records every DATA frame the pump
     * emits. A `read()` past the end of the script is itself a failure: it means
     * the loop pulled a frame it should have had buffered.
     */
    private class ScriptedMuxStream(
        private val chunks: List<ByteArray>,
        private val onSend: suspend (ByteArray) -> Unit = {}
    ) : MuxStream {
        override val id: UInt get() = 1u
        override val incoming: Flow<ByteArray> get() = emptyFlow()
        override var isClosed: Boolean = false
            private set

        var reads: Int = 0
            private set

        val sent = mutableListOf<ByteArray>()

        override suspend fun read(): ByteArray {
            val index = reads++
            check(index < chunks.size) {
                "unexpected extra stream.read(): the loop pulled a DATA frame it should " +
                    "have had buffered already"
            }
            return chunks[index]
        }

        override suspend fun send(data: ByteArray) {
            sent += data
            onSend(data)
        }

        override suspend fun close() {
            isClosed = true
        }
    }

    private companion object {
        /**
         * How many consecutive no-progress engine calls the scripts tolerate
         * before declaring the pump wedged. A correct pump makes exactly one
         * call per scripted status.
         */
        const val MAX_NO_PROGRESS_CALLS = 20

        const val TEST_TIMEOUT_MS = 15_000L
    }
}
