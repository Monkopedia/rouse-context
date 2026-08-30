package com.rousecontext.tunnel

import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLEngineResult.Status
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Timeout

/**
 * What `TlsAcceptor.accept` does with a **cancellation** raised while the
 * handshake is in flight (#644).
 *
 * ## The distinction this file exists to pin
 *
 * `accept` ends in a broad `catch (e: Exception)` that refiles whatever it
 * caught as [TunnelError.TlsHandshakeFailed]. On the JVM that clause catches
 * cancellation too -- `java.util.concurrent.CancellationException` extends
 * `IllegalStateException` extends `Exception`, verified with `javap` against
 * the JDK this module builds on, not from memory. So without a rethrow ahead
 * of it, every ordinary teardown -- service shutdown, scope cancellation, a
 * `withTimeout` expiring, a client going away mid-connect -- came back out of
 * `accept` as a domain *failure*.
 *
 * That is not a cosmetic type error. `TunnelForegroundService` catches
 * `Exception` untyped around the session path and calls
 * `crashReporter.logCaughtException`, so a routine cancelled handshake landed
 * as a non-fatal crash report: lifecycle noise in exactly the channel #616,
 * #626, #630 and #643 spent a week making mean something. It also means a
 * `withTimeout` around `accept` cannot recognise its own timeout -- the same
 * shape as #563's uncancellable spin, one layer up.
 *
 * ## Why the existing cancellation test did not catch it
 *
 * `TlsAcceptorHandshakeStatusTest` already has "cancellation during the
 * handshake write is not reclassified as a defect" (#643). It asserts the
 * throw is not [TunnelError.UnhandledTlsState] and that a
 * [CancellationException] appears *somewhere in the `cause` chain* -- and both
 * were already true of the wrapped `TlsHandshakeFailed`, so it passed against
 * the defect. That test is doing a different, real job (it pins the
 * classify-after-write ordering) and is deliberately left alone; the tests
 * here assert the thrown **type**, which is the property that was missing.
 *
 * ## Both directions, deliberately
 *
 * A fix that propagates cancellation by weakening the wrap would be the
 * mirrored defect -- a genuine handshake failure surfacing as cancellation and
 * being silently ignored by callers that treat cancellation as teardown. So
 * [`an ordinary handshake failure is still reported as TlsHandshakeFailed`]
 * runs in the same class and must stay green in both directions.
 *
 * The scripted [SSLEngine][javax.net.ssl.SSLEngine] harness
 * ([ScriptedSslEngine]) drives the production loop verbatim through a
 * pluggable `SSLContextSpi`, so none of this needs a production seam.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class TlsAcceptCancellationTest {

    // ----------------------------------------------------- cancellation

    @Test
    fun `a withTimeout around accept surfaces the timeout, not a handshake failure`() =
        runBlocking {
            // The realistic shape: the peer never sends its next record, so the
            // pump is parked in stream.read() when the enclosing scope is
            // cancelled -- here by a withTimeout, which cancels the same way a
            // service shutdown or scope teardown does but at a time the test
            // controls. A caller that cannot see its own timeout cannot
            // implement one.
            val acceptor = acceptorFor(SilentPeerEngine(ScriptedSslEngine.nullSession()))

            val thrown = assertFailsWith<Throwable> {
                withTimeout(CANCEL_AFTER_MS) { acceptor.accept(NeverReadsMuxStream()) }
            }

            assertTrue(
                thrown is TimeoutCancellationException,
                "the timeout's own cancellation must reach the caller, got " +
                    "${thrown.javaClass.name}: ${thrown.message}"
            )
            assertFalse(
                TunnelError::class.java.isInstance(thrown),
                "cancellation must not be refiled as a tunnel error, got: $thrown"
            )
        }

    @Test
    fun `cancellation raised during the handshake propagates as cancellation`() = runBlocking {
        // Deterministic counterpart to the timeout test, with no scope
        // cancellation in play at all: the transport write raises cancellation
        // mid-handshake (a mux stream torn down under the pump). It must come
        // back out of accept as itself.
        //
        // No withTimeout here on purpose: a cancellation of our own would be
        // indistinguishable from the timeout's. The class-level @Timeout and
        // the script's no-progress ceiling are the backstops.
        val stream = CancellingWriteMuxStream()
        val acceptor = acceptorFor(HandshakeWriteEngine(ScriptedSslEngine.nullSession()))

        val thrown = assertFailsWith<Throwable> { acceptor.accept(stream) }

        assertTrue(
            thrown is CancellationException,
            "accept must rethrow cancellation, not wrap it -- got " +
                "${thrown.javaClass.name}: ${thrown.message}"
        )
        assertFalse(
            TunnelError::class.java.isInstance(thrown),
            "cancellation must not be laundered into a tunnel error, got: $thrown"
        )
    }

    // -------------------------------------------------- ordinary failure

    @Test
    fun `an ordinary handshake failure is still reported as TlsHandshakeFailed`() = runBlocking {
        // The mirror of the two above. Propagating cancellation must not cost
        // the wrap that real failures depend on: a bad record is a handshake
        // failure, and callers that quietly absorb cancellation would swallow
        // it if it arrived wearing that type.
        val acceptor = acceptorFor(FailingUnwrapEngine(ScriptedSslEngine.nullSession()))

        val thrown = assertFailsWith<TunnelError.TlsHandshakeFailed> {
            withTimeout(TEST_TIMEOUT_MS) { acceptor.accept(OneRecordMuxStream()) }
        }

        assertTrue(
            generateSequence<Throwable>(thrown) { it.cause }.any { it is SSLException },
            "the underlying engine failure must survive as the cause, got: $thrown"
        )
        // Reflective, not `is CancellationException`: the compiler already
        // knows a TlsHandshakeFailed cannot be one and would fold the check to
        // a constant, which is not the same as checking it.
        assertFalse(
            CancellationException::class.java.isInstance(thrown),
            "a real handshake failure must not be dressed up as cancellation, got: $thrown"
        )
    }

    // ------------------------------------------------------------ helpers

    private fun acceptorFor(engine: ScriptedSslEngine): TlsAcceptor =
        TlsAcceptor.create(ScriptedSslContext(engine))

    // ------------------------------------------------------------ engines

    /**
     * Wants a record the peer never sends: the pump parks in `stream.read()`,
     * which is where a real teardown finds it.
     */
    private class SilentPeerEngine(session: SSLSession) : ScriptedSslEngine(session) {
        override fun getHandshakeStatus(): HandshakeStatus = HandshakeStatus.NEED_UNWRAP

        override fun scriptedUnwrap(src: ByteBuffer, dst: ByteBuffer): SSLEngineResult =
            error("silent-peer script: no record ever arrives, so unwrap is never reached")
    }

    /**
     * One clean `wrap` that would finish the handshake. Nothing here is a
     * defect -- the cancellation in that test comes from the transport, so the
     * engine deliberately stays out of the way and the test cannot be passing
     * for a status-classification reason.
     */
    private class HandshakeWriteEngine(session: SSLSession) : ScriptedSslEngine(session) {
        override fun getHandshakeStatus(): HandshakeStatus = HandshakeStatus.NEED_WRAP

        override fun scriptedUnwrap(src: ByteBuffer, dst: ByteBuffer): SSLEngineResult =
            error("handshake-write script: the read path is not exercised here")

        override fun scriptedWrap(src: ByteBuffer, dst: ByteBuffer): SSLEngineResult {
            check(wrapCalls <= MAX_NO_PROGRESS_CALLS) {
                "the handshake pump re-wrapped $wrapCalls times without making progress"
            }
            dst.put(RECORD)
            return SSLEngineResult(Status.OK, HandshakeStatus.FINISHED, 0, RECORD.size)
        }
    }

    /** An ordinary bad-record failure: `unwrap` throws the way SunJSSE does. */
    private class FailingUnwrapEngine(session: SSLSession) : ScriptedSslEngine(session) {
        override fun getHandshakeStatus(): HandshakeStatus = HandshakeStatus.NEED_UNWRAP

        override fun scriptedUnwrap(src: ByteBuffer, dst: ByteBuffer): SSLEngineResult =
            throw SSLException("bad record MAC")
    }

    // ------------------------------------------------------------ streams

    /** Never yields a record, so the pump stays parked until it is cancelled. */
    private class NeverReadsMuxStream : MuxStream {
        override val id: UInt get() = 1u
        override val incoming: Flow<ByteArray> get() = emptyFlow()
        override var isClosed: Boolean = false
            private set

        override suspend fun read(): ByteArray = awaitCancellation()

        override suspend fun send(data: ByteArray) = Unit

        override suspend fun close() {
            isClosed = true
        }
    }

    /** The transport is torn down under the pump: the write raises cancellation. */
    private class CancellingWriteMuxStream : MuxStream {
        override val id: UInt get() = 1u
        override val incoming: Flow<ByteArray> get() = emptyFlow()
        override var isClosed: Boolean = false
            private set

        override suspend fun read(): ByteArray =
            error("cancelling-write script: the read path is not exercised here")

        override suspend fun send(data: ByteArray): Unit =
            throw CancellationException("mux stream torn down mid-handshake")

        override suspend fun close() {
            isClosed = true
        }
    }

    /** Hands the pump exactly one record, which the engine then rejects. */
    private class OneRecordMuxStream : MuxStream {
        override val id: UInt get() = 1u
        override val incoming: Flow<ByteArray> get() = emptyFlow()
        override var isClosed: Boolean = false
            private set

        private var reads = 0

        override suspend fun read(): ByteArray {
            check(reads++ == 0) { "unexpected extra stream.read() after the engine rejected" }
            return RECORD
        }

        override suspend fun send(data: ByteArray) = Unit

        override suspend fun close() {
            isClosed = true
        }
    }

    private companion object {
        val RECORD = "ciphertext".toByteArray()

        /**
         * How many `wrap` calls a script tolerates before declaring the pump
         * wedged. A correct pump makes exactly one per scripted status.
         */
        const val MAX_NO_PROGRESS_CALLS = 20

        /**
         * Long enough that the pump has certainly parked in `stream.read()`,
         * short enough that the test is quick. The class-level `@Timeout` is
         * the backstop if cancellation is not delivered at all.
         */
        const val CANCEL_AFTER_MS = 500L

        const val TEST_TIMEOUT_MS = 15_000L
    }
}
