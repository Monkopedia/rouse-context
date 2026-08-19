package com.rousecontext.tunnel

import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Regression test for issue #569.
 *
 * #567 made inbound mux frames drain on a single consumer coroutine, launched
 * on the injected `scope`. In production that scope is
 * `CoroutineScope(SupervisorJob() + Dispatchers.Main)`, so every inbound frame
 * in the app was demultiplexed on the main thread, one at a time: anything else
 * occupying Main — a slow recomposition, a long `withContext(Dispatchers.Main)`
 * block — stalled the whole tunnel. Worse, a long enough stall overflows the
 * bounded inbound queue, and that tear-down names the *reader* as the culprit,
 * so the log would point at the peer while the real cause was a starved
 * consumer.
 *
 * The consumer therefore runs on [kotlinx.coroutines.Dispatchers.Default]
 * regardless of what the caller injects. Ordering is unaffected — it comes from
 * there being exactly one consumer draining the queue, not from the dispatcher
 * being single-threaded (see [MuxFrameOrderingTest], which asserts wire order
 * under multi-threaded dispatchers).
 */
class InboundFrameConsumerDispatcherTest {

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val NONCE = 0xC0FFEEuL
    }

    /**
     * The whole point of the fix: the tunnel keeps demultiplexing while the
     * injected scope's only thread is wedged, the way Main wedges behind a slow
     * frame. An inbound Ping is echoed as a Pong straight out of
     * `handleFrame`, so the reply is proof the consumer ran — and it does not
     * touch the blocked dispatcher on its way out.
     */
    @Test
    fun `frames are demultiplexed while the injected scope's only thread is blocked`() =
        runBlocking {
            val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "main-like") }
            val appScope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
            // Frees the wedged thread so the executor can shut down.
            val release = CompletableDeferred<Unit>()
            try {
                val pong = CompletableDeferred<MuxFrame>()
                val factory = DirectWebSocketFactory(
                    ScriptedHandle { pong.complete(MuxCodec.decode(it)) }
                )
                val client = TunnelClientImpl(appScope, factory)
                client.connect("ws://consumer-dispatcher.invalid/tunnel")
                assertEquals(TunnelState.CONNECTED, client.state.value)

                // Occupy the scope's single thread for the rest of the test.
                // Blocking rather than suspending is the point: a suspended
                // coroutine gives the dispatcher back, a busy main thread does
                // not.
                val wedged = CompletableDeferred<Unit>()
                appScope.launch {
                    wedged.complete(Unit)
                    runBlocking { release.await() }
                }
                withTimeout(TIMEOUT_MS) { wedged.await() }

                factory.deliver(MuxFrame.Ping(NONCE))

                val reply = withTimeout(TIMEOUT_MS) { pong.await() }
                assertTrue(
                    reply is MuxFrame.Pong && reply.nonce == NONCE,
                    "expected the Pong echo for $NONCE, got $reply"
                )

                coroutineContext.cancelChildren()
            } finally {
                release.complete(Unit)
                appScope.cancel()
                executor.shutdownNow()
            }
        }

    /**
     * A [WebSocketFactory] with no transport at all: [deliver] pushes an
     * encoded frame straight into the listener from the calling thread, the way
     * a WebSocket reader loop does.
     */
    private class DirectWebSocketFactory(private val handle: WebSocketHandle) : WebSocketFactory {
        @Volatile
        private var listener: WebSocketListener? = null

        override fun connect(url: String, listener: WebSocketListener): WebSocketHandle {
            this.listener = listener
            listener.onOpen()
            return handle
        }

        fun deliver(frame: MuxFrame) {
            val target = checkNotNull(listener) { "connect() has not been called" }
            target.onBinaryMessage(MuxCodec.encode(frame))
        }
    }

    /** A handle whose binary writes run [onSendBinary]. */
    private class ScriptedHandle(private val onSendBinary: suspend (ByteArray) -> Unit) :
        WebSocketHandle {
        override suspend fun sendBinary(data: ByteArray): Boolean {
            onSendBinary(data)
            return true
        }

        override suspend fun sendText(text: String): Boolean = true

        override suspend fun close(code: Int, reason: String) = Unit
    }
}
