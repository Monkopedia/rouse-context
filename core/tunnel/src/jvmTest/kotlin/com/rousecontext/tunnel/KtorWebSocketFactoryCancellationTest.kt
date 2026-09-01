package com.rousecontext.tunnel

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.websocket.WebSockets
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Timeout

/**
 * What [KtorWebSocketFactory] does when its scope is torn down while the
 * WebSocket handshake is still in flight (#646).
 *
 * The broad `catch (Exception)` around the whole session lifecycle reported a
 * cancelled connect to the listener as `onFailure` / `onClosing(1006, …)` --
 * a transport *failure* synthesised out of a scope teardown, which is the
 * mirror image of the defect #644 fixed one layer up in
 * `TunnelClientImpl.connect`.
 *
 * The handle's `sessionDeferred` is still completed on the cancellation path.
 * That is the whole point of `failBind` (#420 finding #4): an awaiter parked in
 * `sendBinary` has no other way to learn the session will never arrive.
 * Completing it *with the cancellation* both unblocks the awaiter and tells it
 * the truth, where reporting `onFailure` did neither. Only the listener
 * notification is dropped.
 *
 * The guard is gated on the job rather than on the exception type. A
 * cancellation raised by the *session* while this coroutine is still active is
 * a transport failure the listener must still hear about -- otherwise the
 * tunnel sits CONNECTED over a dead socket, the #558 symptom. The second test
 * pins that branch so the discriminator is not just asserted in a comment.
 *
 * The peer for the teardown test is a bound-but-never-accepted [ServerSocket]: the TCP
 * connection completes out of the accept backlog and the HTTP upgrade response
 * never arrives, so the launched coroutine is reliably parked inside the `try`
 * when the cancellation lands. [KtorWebSocketFactoryFailBindTest] pins the
 * ordinary connect-failure direction against a dead port.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class KtorWebSocketFactoryCancellationTest {

    @Test
    fun `a cancelled handshake is not reported to the listener as a failure`() = runBlocking {
        val peer = ServerSocket(0)
        try {
            val listener = RecordingListener()
            val inner = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
            val factory = KtorWebSocketFactory(scope = inner)

            factory.connect("ws://127.0.0.1:${peer.localPort}/tunnel", listener)
            // Long enough that the upgrade request is certainly sent and the
            // coroutine is parked waiting for a response that never comes.
            delay(HANDSHAKE_SETTLE_MS)
            assertTrue(
                listener.failures.isEmpty() && listener.closings.isEmpty(),
                "precondition: the handshake must still be in flight, but the listener " +
                    "already saw failures=${listener.failures} closings=${listener.closings}"
            )

            inner.cancel()
            delay(TEARDOWN_SETTLE_MS)

            assertTrue(
                listener.failures.isEmpty(),
                "a cancelled handshake is teardown, not a transport failure, but the " +
                    "listener was told: ${listener.failures}"
            )
            assertTrue(
                listener.closings.isEmpty(),
                "a cancelled handshake must not synthesise a close, but got " +
                    "${listener.closings}"
            )
        } finally {
            peer.close()
            coroutineContext.cancelChildren()
        }
    }

    @Test
    fun `a cancellation from the session is still reported to the listener`() = runBlocking {
        // The caller is not cancelled: the session itself raises cancellation.
        // That is a broken transport, not teardown, so the listener must be told
        // -- and the awaiter must still be released.
        val listener = RecordingListener()
        val factory = KtorWebSocketFactory(
            scope = this,
            httpClient = HttpClient(MockEngine { throw CancellationException("session died") }) {
                install(WebSockets)
            }
        )

        factory.connect("ws://relay.test/tunnel", listener)

        withTimeout(FAILURE_TIMEOUT_MS) { listener.firstFailure.await() }

        coroutineContext.cancelChildren()
    }

    @Test
    fun `a genuine connect failure is still reported to the listener`() = runBlocking {
        val listener = RecordingListener()

        // Nothing is listening on port 1, so webSocketSession() throws before
        // the session is bound -- the path KtorWebSocketFactoryFailBindTest
        // covers from the awaiter's side.
        KtorWebSocketFactory(scope = this).connect("ws://127.0.0.1:1/tunnel", listener)

        withTimeout(FAILURE_TIMEOUT_MS) { listener.firstFailure.await() }

        coroutineContext.cancelChildren()
    }

    private class RecordingListener : WebSocketListener {
        val failures = mutableListOf<Throwable>()
        val closings = mutableListOf<Pair<Int, String>>()
        val firstFailure = CompletableDeferred<Throwable>()

        override fun onOpen() = Unit

        override fun onBinaryMessage(data: ByteArray) = Unit

        override fun onClosing(code: Int, reason: String) {
            closings += code to reason
        }

        override fun onFailure(error: Throwable) {
            failures += error
            firstFailure.complete(error)
        }
    }

    private companion object {
        const val HANDSHAKE_SETTLE_MS = 500L
        const val TEARDOWN_SETTLE_MS = 500L
        const val FAILURE_TIMEOUT_MS = 5_000L
    }
}
