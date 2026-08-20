package com.rousecontext.tunnel

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Regression tests for issues #568 and #576.
 *
 * #567 serialised inbound mux frames onto a single consumer coroutine to fix
 * the ordering bug in #562. That is the right shape, but it also means the
 * consumer is a single point of failure: it was the *only* caller of
 * [MuxDemux.handleFrame], and it caught only `ClosedSendChannelException`. Any
 * other throw killed it, and nothing noticed — the socket stayed up, the state
 * machine stayed `CONNECTED`, no [TunnelError] was emitted, and every later
 * frame went nowhere. That is exactly the #558 symptom: the device side goes
 * silent and the peer dies on its own timeout with nothing naming the cause.
 *
 * The throw is produced the way production would produce it: `handleFrame`
 * echoes an inbound Ping as a Pong through `onOutgoingFrame`, which is wired
 * straight to `WebSocketHandle.sendBinary`. A socket that fails a write there
 * throws out of `handleFrame`.
 */
class InboundFrameConsumerFailureTest {

    private companion object {
        const val TIMEOUT_MS = 10_000L

        /** How long to watch for an error that must never arrive. */
        const val QUIET_MS = 500L

        const val NONCE = 0xFEEDuL

        const val STREAM_ID = 7u
    }

    /**
     * The tunnel-level assertion: an unexpected throw out of `handleFrame`
     * must reach the state machine, not just the consumer's stack.
     */
    @Test
    fun `a throw from handleFrame tears the tunnel down instead of going quiet`() = runBlocking {
        val ioScope = CoroutineScope(Dispatchers.IO)
        try {
            val factory = DirectWebSocketFactory(
                ScriptedHandle(onSendBinary = { throw IOException("socket write failed") })
            )
            val client = TunnelClientImpl(ioScope, factory)
            client.connect("ws://mux-handler-throw.invalid/tunnel")
            assertEquals(TunnelState.CONNECTED, client.state.value)

            val errorReceived = CompletableDeferred<TunnelError>()
            val subscribed = CompletableDeferred<Unit>()
            val collect = launch {
                client.errors
                    .onSubscription { subscribed.complete(Unit) }
                    .collect { errorReceived.complete(it) }
            }
            subscribed.await()

            factory.deliver(MuxFrame.Ping(NONCE))

            val error = withTimeout(TIMEOUT_MS) { errorReceived.await() }
            assertTrue(
                error is TunnelError.ConnectionFailed,
                "expected a named ConnectionFailed, got ${error::class.simpleName}"
            )
            assertTrue(
                error.message.orEmpty().contains("Ping"),
                "error must name the frame that failed, got: ${error.message}"
            )
            withTimeout(TIMEOUT_MS) { client.state.first { it == TunnelState.DISCONNECTED } }

            collect.cancel()
            coroutineContext.cancelChildren()
        } finally {
            ioScope.cancel()
        }
    }

    /**
     * The guard on the fix. Broadening the catch must not swallow
     * `CancellationException`: that would break cooperative cancellation, which
     * is a worse bug than the one being fixed.
     *
     * Cancellation is driven through [TunnelClient.disconnect], the production
     * path that cancels this coroutine (`cleanupRefs` cancels `inboundJob`).
     * Unlike cancelling the whole scope it leaves `scope` alive, so a swallowed
     * `CancellationException` would be *observable* as a spurious tunnel error
     * rather than being masked by a dead scope that cannot launch anything.
     */
    @Test
    fun `cancelling the consumer mid-frame terminates it without reporting an error`() =
        runBlocking {
            val ioScope = CoroutineScope(Dispatchers.IO)
            try {
                // Never completed: the consumer parks inside handleFrame at a
                // cancellable suspension point and stays there until cancelled.
                val wedged = CompletableDeferred<Unit>()
                val insideHandleFrame = CompletableDeferred<Unit>()
                val factory = DirectWebSocketFactory(
                    ScriptedHandle(
                        onSendBinary = {
                            insideHandleFrame.complete(Unit)
                            wedged.await()
                        }
                    )
                )
                val client = TunnelClientImpl(ioScope, factory)
                client.connect("ws://mux-handler-cancel.invalid/tunnel")

                val errorReceived = CompletableDeferred<TunnelError>()
                val subscribed = CompletableDeferred<Unit>()
                val collect = launch {
                    client.errors
                        .onSubscription { subscribed.complete(Unit) }
                        .collect { errorReceived.complete(it) }
                }
                subscribed.await()

                factory.deliver(MuxFrame.Ping(NONCE))
                withTimeout(TIMEOUT_MS) { insideHandleFrame.await() }

                client.disconnect()

                assertEquals(TunnelState.DISCONNECTED, client.state.value)
                assertNull(
                    withTimeoutOrNull(QUIET_MS) { errorReceived.await() },
                    "cancellation must not be reported as a tunnel error"
                )

                collect.cancel()
                coroutineContext.cancelChildren()
            } finally {
                ioScope.cancel()
            }
        }

    /**
     * The guard on the first of the consumer's three catch clauses
     * (issue #576).
     *
     * `ClosedSendChannelException` is deliberately tolerated: a stream or the
     * demux torn down while a frame was in flight is routine, not a tunnel
     * failure. Nothing pinned that, so collapsing the three catches into one
     * would have landed green while turning every benign teardown race into a
     * full reconnect.
     *
     * The throw comes from production code, not from a test double.
     * `disconnect()` closes the demux — and with it the channel behind
     * [MuxDemux.incomingStreams] — *before* `cleanupRefs` closes the frame
     * queue and cancels the consumer. The transport close is held mid-flight
     * (a real WebSocket close writes a close frame and waits for the peer's
     * ack, so it is not instant), which keeps that window open: an OPEN frame
     * already on the queue is then handled against the closed channel and
     * `MuxDemux.handleOpen` throws `ClosedSendChannelException` out of
     * `incomingChannel.send`.
     *
     * The load-bearing assertion is the Pong. Both frames ride the one ordered
     * queue, so the Ping is handled strictly after the OPEN that threw — it can
     * only be echoed if the consumer *survived* the throw. Under the broad
     * catch the consumer would instead have returned from `failInboundConsumer`
     * with the queue closed, and the Ping would go nowhere.
     */
    @Test
    fun `a frame racing the demux teardown is tolerated and the consumer keeps draining`() =
        runBlocking {
            val ioScope = CoroutineScope(Dispatchers.IO)
            try {
                val outgoing = Channel<MuxFrame>(Channel.BUFFERED)
                val closeStarted = CompletableDeferred<Unit>()
                // Never completed: parks disconnect() inside the transport
                // close so the teardown window stays open for the assertions.
                val closeWedged = CompletableDeferred<Unit>()
                val factory = DirectWebSocketFactory(
                    ScriptedHandle(
                        onSendBinary = { outgoing.send(MuxCodec.decode(it)) },
                        onClose = {
                            closeStarted.complete(Unit)
                            closeWedged.await()
                        }
                    )
                )
                val client = TunnelClientImpl(ioScope, factory)
                client.connect("ws://mux-teardown-race.invalid/tunnel")
                assertEquals(TunnelState.CONNECTED, client.state.value)

                val errorReceived = CompletableDeferred<TunnelError>()
                val subscribed = CompletableDeferred<Unit>()
                val collect = launch {
                    client.errors
                        .onSubscription { subscribed.complete(Unit) }
                        .collect { errorReceived.complete(it) }
                }
                subscribed.await()

                val disconnecting = launch { client.disconnect() }
                withTimeout(TIMEOUT_MS) { closeStarted.await() }

                factory.deliver(MuxFrame.Open(STREAM_ID))
                factory.deliver(MuxFrame.Ping(NONCE))

                assertEquals(
                    MuxFrame.Pong(NONCE),
                    withTimeout(TIMEOUT_MS) { outgoing.receive() },
                    "the frame after the tolerated one must still be handled"
                )
                assertNull(
                    withTimeoutOrNull(QUIET_MS) { errorReceived.await() },
                    "a benign teardown race must not be reported as a tunnel error"
                )
                // Not `assertEquals(CONNECTED, ...)`: handleOpen increments
                // activeStreamCount before the send that throws, so the tunnel
                // may legitimately have stepped to ACTIVE. What must not have
                // happened is a teardown.
                assertNotEquals(
                    TunnelState.DISCONNECTED,
                    client.state.value,
                    "a benign teardown race must not tear the tunnel down"
                )

                disconnecting.cancel()
                collect.cancel()
                coroutineContext.cancelChildren()
            } finally {
                ioScope.cancel()
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

    /** A handle whose binary writes run [onSendBinary] and whose close runs [onClose]. */
    private class ScriptedHandle(
        private val onSendBinary: suspend (ByteArray) -> Unit,
        private val onClose: suspend () -> Unit = {}
    ) : WebSocketHandle {
        override suspend fun sendBinary(data: ByteArray): Boolean {
            onSendBinary(data)
            return true
        }

        override suspend fun sendText(text: String): Boolean = true

        override suspend fun close(code: Int, reason: String) = onClose()
    }
}
