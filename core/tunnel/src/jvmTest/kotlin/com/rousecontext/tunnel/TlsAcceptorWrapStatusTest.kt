package com.rousecontext.tunnel

import java.io.IOException
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
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Timeout

/**
 * How `SuspendTlsSession.write` classifies each `SSLEngineResult.Status` the
 * engine can report from `wrap` (#630).
 *
 * ## The enumeration
 *
 * `javax.net.ssl.SSLEngineResult.Status` is a closed four-member enum --
 * verified with `javap javax.net.ssl.SSLEngineResult$Status` against the JDK
 * this module builds on, not from memory:
 *
 * | status            | wrap path                | why                                     |
 * |-------------------|--------------------------|-----------------------------------------|
 * | `OK`              | ordinary -- continue     | a record was produced; keep draining.   |
 * | `CLOSED`          | ordinary -- quiet end    | the peer hung up. Routine on a bridge.  |
 * | `BUFFER_OVERFLOW` | DEFECT                   | see below.                              |
 * | `BUFFER_UNDERFLOW`| DEFECT                   | see below.                              |
 *
 * `BUFFER_OVERFLOW`: `netOut` is allocated at `session.packetBufferSize` and
 * `clear()`ed before every `wrap`, so the destination always offers the exact
 * maximum the engine advertises it can ever emit for one record. Overflow into
 * that buffer means the engine broke its own advertised bound -- an invariant
 * this layer owns, not a resource shortfall we can allocate our way out of.
 *
 * `BUFFER_UNDERFLOW`: means "the source does not hold a complete TLS record",
 * which is an `unwrap` concept. `wrap` has no minimum input; it encrypts
 * whatever plaintext it is handed, zero bytes included. Reporting underflow
 * from `wrap` is a contract violation.
 *
 * ## Why the read path classifies the same statuses differently
 *
 * `SuspendTlsSession.read` treats **both** buffer statuses as ordinary, and it
 * is right to:
 *
 * - `BUFFER_UNDERFLOW` on unwrap is the everyday case of a TLS record split
 *   across two mux DATA frames -- the whole point of #558's `needMoreNetData`.
 * - `BUFFER_OVERFLOW` on unwrap targets `appIn`, a buffer the caller drains
 *   incrementally and whose sizing is only a hint, so growing and retrying is
 *   real recovery.
 *
 * Neither argument survives the trip to the write side: the wrap destination is
 * private, freshly cleared, and sized to the engine's own stated maximum. Same
 * enum, different invariant, so the two directions legitimately disagree.
 *
 * ## Why the type matters
 *
 * A plain `IOException` cannot carry this distinction: `SuspendTlsSession.write`
 * already reports an ordinary dead peer as `IOException("TLS write failed:
 * stream closed", e)`, and `SessionHandler.copyStreamToTls` must keep swallowing
 * those -- making routine disconnects noisy trains whoever reads the log to
 * ignore it. `TunnelError.UnhandledTlsState` (added by #626) is the discriminator
 * that loop already catches and rethrows. See [WrapDefectReachesSessionHandlerTest]
 * for the boundary end of the same story.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class TlsAcceptorWrapStatusTest {

    // ------------------------------------------------------------- defects

    @Test
    fun `wrap reporting BUFFER_OVERFLOW is a defect, not a disconnect`() = runBlocking {
        val session = sessionWrapping(Status.BUFFER_OVERFLOW)

        val thrown = assertFailsWith<TunnelError.UnhandledTlsState> {
            withTimeout(TEST_TIMEOUT_MS) { session.write(PAYLOAD) }
        }

        assertTrue(
            thrown.message.orEmpty().contains("BUFFER_OVERFLOW"),
            "the throw must name the status it could not handle, got: ${thrown.message}"
        )
    }

    @Test
    fun `wrap reporting BUFFER_UNDERFLOW is a defect, not a disconnect`() = runBlocking {
        val session = sessionWrapping(Status.BUFFER_UNDERFLOW)

        val thrown = assertFailsWith<TunnelError.UnhandledTlsState> {
            withTimeout(TEST_TIMEOUT_MS) { session.write(PAYLOAD) }
        }

        assertTrue(
            thrown.message.orEmpty().contains("BUFFER_UNDERFLOW"),
            "the throw must name the status it could not handle, got: ${thrown.message}"
        )
    }

    // ------------------------------------------------------------ ordinary

    @Test
    fun `wrap reporting CLOSED stays an ordinary IOException`() = runBlocking {
        // The common case by a wide margin: the peer went away mid-response.
        // It must NOT be reported as a defect -- `copyStreamToTls` swallows
        // plain IOExceptions on purpose so a disconnect ends the loop quietly.
        val session = sessionWrapping(Status.CLOSED)

        val thrown = assertFailsWith<IOException> {
            withTimeout(TEST_TIMEOUT_MS) { session.write(PAYLOAD) }
        }

        // Reflective, not `is TunnelError`: the compiler already knows an
        // IOException cannot be one, and would fold the check to a constant.
        assertFalse(
            TunnelError::class.java.isInstance(thrown),
            "an ordinary CLOSED wrap must not be filed as a tunnel defect, got: $thrown"
        )
    }

    @Test
    fun `wrap reporting OK writes the record and returns`() = runBlocking {
        val record = "ciphertext".toByteArray()
        val stream = RecordingMuxStream()
        val session = sessionWrapping(Status.OK, produce = record, stream = stream)

        withTimeout(TEST_TIMEOUT_MS) { session.write(PAYLOAD) }

        assertEquals(1, stream.sent.size, "one wrap of one payload should emit one DATA frame")
        assertContentEquals(record, stream.sent.single())
    }

    // -------------------------------------------------------- cancellation

    @Test
    fun `cancellation during the underlying write is not reported as a defect`() = runBlocking {
        // The engine has a defect status queued AND the transport write is
        // cancelled. Cancellation wins: it must propagate as itself, never be
        // reclassified. This file has already produced one uncancellable spin
        // (#563), so the rule gets a test rather than a comment.
        val stream = RecordingMuxStream(onSend = { throw CancellationException("cancelled") })
        val session = sessionWrapping(
            Status.BUFFER_OVERFLOW,
            produce = "ciphertext".toByteArray(),
            stream = stream
        )

        // No `withTimeout` here: a CancellationException of our own would be
        // indistinguishable from the timeout's. The class-level @Timeout and the
        // script's no-progress ceiling are the backstops.
        val thrown = assertFailsWith<CancellationException> { session.write(PAYLOAD) }

        assertFalse(
            TunnelError::class.java.isInstance(thrown),
            "cancellation must not be laundered into a tunnel error, got: $thrown"
        )
    }

    // ------------------------------------------------------------- helpers

    private suspend fun sessionWrapping(
        status: Status,
        produce: ByteArray = ByteArray(0),
        stream: MuxStream = RecordingMuxStream()
    ): TlsAcceptor.TlsSession {
        val sslSession = ScriptedSslEngine.nullSession()
        val engine = WrapStatusEngine(sslSession, status, produce)
        return TlsAcceptor.create(ScriptedSslContext(engine)).accept(stream)
    }

    // ------------------------------------------------------------- engines

    /**
     * Reports [status] from every `wrap`, having produced [produce] into the
     * destination. Consumes the source only on `OK`, which is what makes a
     * non-`OK` status that the production loop fails to classify show up as an
     * endless re-wrap rather than a pass.
     *
     * `getHandshakeStatus` is `NOT_HANDSHAKING`, so `TlsAcceptor.accept` skips
     * the pump entirely and hands back a session without any unwrap.
     */
    private class WrapStatusEngine(
        session: SSLSession,
        private val status: Status,
        private val produce: ByteArray
    ) : ScriptedSslEngine(session) {

        override fun getHandshakeStatus(): HandshakeStatus = HandshakeStatus.NOT_HANDSHAKING

        override fun scriptedUnwrap(src: ByteBuffer, dst: ByteBuffer): SSLEngineResult =
            error("wrap-status script: the read path is not exercised here")

        override fun scriptedWrap(src: ByteBuffer, dst: ByteBuffer): SSLEngineResult {
            check(wrapCalls <= MAX_NO_PROGRESS_CALLS) {
                "write() re-wrapped the same bytes $wrapCalls times on a $status status: " +
                    "the loop neither classified it nor made progress (#630)"
            }
            dst.put(produce)
            val consumed = if (status == Status.OK) src.remaining() else 0
            if (status == Status.OK) src.position(src.limit())
            return SSLEngineResult(status, HandshakeStatus.NOT_HANDSHAKING, consumed, produce.size)
        }
    }

    // ------------------------------------------------------------- streams

    /** Records every DATA frame the session emits; never yields inbound data. */
    private class RecordingMuxStream(private val onSend: suspend (ByteArray) -> Unit = {}) :
        MuxStream {
        override val id: UInt get() = 1u
        override val incoming: Flow<ByteArray> get() = emptyFlow()
        override var isClosed: Boolean = false
            private set

        val sent = mutableListOf<ByteArray>()

        override suspend fun send(data: ByteArray) {
            sent += data
            onSend(data)
        }

        override suspend fun close() {
            isClosed = true
        }

        override suspend fun read(): ByteArray = awaitCancellation()
    }

    private companion object {
        val PAYLOAD = "plaintext to encrypt".toByteArray()

        /**
         * How many `wrap` calls the script tolerates before declaring the write
         * loop wedged. A correct loop makes exactly one per non-`OK` status.
         */
        const val MAX_NO_PROGRESS_CALLS = 20

        const val TEST_TIMEOUT_MS = 15_000L
    }
}
