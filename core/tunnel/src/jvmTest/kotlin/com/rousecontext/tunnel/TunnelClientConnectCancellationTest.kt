package com.rousecontext.tunnel

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Timeout

/**
 * What `TunnelClientImpl.connect` does with a **cancellation** raised while the
 * WebSocket handshake is still in flight (#644).
 *
 * The second instance of the same defect the issue is about. `connect` parks in
 * `opened.await()` until the transport reports `onOpen`, and its broad
 * `catch (e: Exception)` had no cancellation rethrow ahead of it -- so a scope
 * teardown at that moment was refiled as `TunnelError.ConnectionFailed` and
 * went out through *two* channels at once:
 *
 *  - thrown to `TunnelForegroundService.connectToRelay`, whose untyped
 *    `catch (e: Exception)` calls `crashReporter.logCaughtException`; and
 *  - published on `errors`, which the app collects and surfaces to the user.
 *
 * Neither is true of a cancelled connect: the caller went away, the connection
 * did not fail. Cancellation must come back as itself.
 *
 * The cleanup is deliberately still performed on the cancellation path -- the
 * inbound queue is closed and the state machine moves to `DISCONNECTED`, since
 * leaving a half-built connection at `CONNECTING` would strand the client.
 * Only the *error report* is dropped, and this test pins both halves.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class TunnelClientConnectCancellationTest {

    @Test
    fun `cancelling connect propagates cancellation, not a ConnectionFailed`() = runBlocking {
        // The transport never reports onOpen, so connect is parked in
        // opened.await() -- exactly where a service shutdown finds it. The
        // withTimeout cancels the same way that teardown would, at a moment the
        // test controls.
        val client = TunnelClientImpl(this, NeverOpensWebSocketFactory())
        val errors = mutableListOf<TunnelError>()
        val errorJob = launch { client.errors.collect { errors += it } }
        delay(COLLECTOR_SETTLE_MS)

        val thrown = assertFailsWith<Throwable> {
            withTimeout(CANCEL_AFTER_MS) { client.connect("ws://localhost:1/tunnel") }
        }

        assertTrue(
            thrown is TimeoutCancellationException,
            "the caller's own cancellation must reach it, got " +
                "${thrown.javaClass.name}: ${thrown.message}"
        )
        assertFalse(
            TunnelError::class.java.isInstance(thrown),
            "cancellation must not be refiled as a tunnel error, got: $thrown"
        )

        delay(COLLECTOR_SETTLE_MS)
        assertTrue(
            errors.isEmpty(),
            "a cancelled connect is teardown, not a failure: it must publish nothing on " +
                "errors, but published $errors"
        )
        assertEquals(
            TunnelState.DISCONNECTED,
            client.state.value,
            "the cancellation path must still leave the client disconnected rather than " +
                "stranded at CONNECTING"
        )

        errorJob.cancel()
        coroutineContext.cancelChildren()
    }

    @Test
    fun `a cancellation from the transport propagates as cancellation`() = runBlocking {
        // Deterministic counterpart with no scope cancellation in play: the
        // transport itself raises cancellation as the connection is being set
        // up. It must come back out of connect as itself.
        //
        // No withTimeout here on purpose: a cancellation of our own would be
        // indistinguishable from the one under test. The class-level @Timeout is
        // the backstop.
        val client = TunnelClientImpl(this, CancellingWebSocketFactory())
        val errors = mutableListOf<TunnelError>()
        val errorJob = launch { client.errors.collect { errors += it } }
        delay(COLLECTOR_SETTLE_MS)

        val thrown = assertFailsWith<Throwable> { client.connect("ws://localhost:1/tunnel") }

        assertTrue(
            thrown is CancellationException,
            "connect must rethrow cancellation, not wrap it -- got " +
                "${thrown.javaClass.name}: ${thrown.message}"
        )
        assertFalse(
            TunnelError::class.java.isInstance(thrown),
            "cancellation must not be laundered into a tunnel error, got: $thrown"
        )

        delay(COLLECTOR_SETTLE_MS)
        assertTrue(errors.isEmpty(), "nothing may be published on errors, but got $errors")

        errorJob.cancel()
        coroutineContext.cancelChildren()
    }

    /**
     * Opens nothing and reports nothing: `connect` suspends in `opened.await()`
     * indefinitely. A factory that failed instead would exercise the ordinary
     * `ConnectionFailed` path, which [TunnelClientImplTest] already covers.
     */
    private class NeverOpensWebSocketFactory : WebSocketFactory {
        override fun connect(url: String, listener: WebSocketListener): WebSocketHandle =
            SilentHandle()
    }

    /** The transport is torn down as the connection is set up. */
    private class CancellingWebSocketFactory : WebSocketFactory {
        override fun connect(url: String, listener: WebSocketListener): WebSocketHandle =
            throw CancellationException("tunnel scope torn down mid-connect")
    }

    private class SilentHandle : WebSocketHandle {
        override suspend fun sendBinary(data: ByteArray): Boolean = true

        override suspend fun sendText(text: String): Boolean = true

        override suspend fun close(code: Int, reason: String) = Unit
    }

    private companion object {
        /**
         * Long enough for `connect` to have certainly reached `opened.await()`,
         * short enough to keep the test quick. The class-level `@Timeout` is the
         * backstop if the cancellation is never delivered.
         */
        const val CANCEL_AFTER_MS = 500L

        const val COLLECTOR_SETTLE_MS = 100L
    }
}
