package com.rousecontext.tunnel

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Regression tests for issue #562: mux DATA frames must reach [MuxStream] in
 * wire order.
 *
 * The mux protocol carries no sequence numbers, so nothing downstream can
 * repair a reordering. A swapped pair of TLS records fails `SSLEngine.unwrap`
 * with `bad_record_mac`, which the bridge swallows as EOF — the observable
 * symptom is a dead tunnel with nothing in any log naming the cause.
 *
 * Both tests deliberately run [TunnelClientImpl] on [Dispatchers.IO]. Ordering
 * must be a property of the tunnel itself, not of whichever dispatcher the
 * caller happens to inject: production passes a `Dispatchers.Main` scope, which
 * made the old one-coroutine-per-frame dispatch *accidentally* FIFO. These
 * tests remove that accident.
 */
class MuxFrameOrderingTest {

    private companion object {
        const val FRAMES = 400
        const val STREAM_ID = 1u
    }

    /**
     * Hermetic reproducer. Frames are handed to [WebSocketListener] one at a
     * time from a single thread — exactly how a WebSocket reader loop delivers
     * them — so the only possible source of reordering is the tunnel's own
     * dispatch.
     */
    @Test
    fun `DATA frames handed over in wire order reach the stream in wire order`() = runBlocking {
        val ioScope = CoroutineScope(Dispatchers.IO)
        try {
            val factory = DirectWebSocketFactory()
            val client = TunnelClientImpl(ioScope, factory)
            client.connect("ws://mux-ordering.invalid/tunnel")

            val streamDeferred = CompletableDeferred<MuxStream>()
            val collect = launch {
                client.incomingSessions.collect { streamDeferred.complete(it) }
            }

            factory.deliver(MuxFrame.Open(STREAM_ID))
            val stream = withTimeout(10_000) { streamDeferred.await() }

            val seen = ArrayList<Int>(FRAMES)
            val reader = launch(Dispatchers.IO) {
                repeat(FRAMES) { seen.add(String(stream.read()).toInt()) }
            }

            for (i in 0 until FRAMES) {
                factory.deliver(MuxFrame.Data(STREAM_ID, i.toString().toByteArray()))
            }
            withTimeout(30_000) { reader.join() }

            assertEquals(null, firstDisorder(seen), "mux DATA frames arrived out of order")

            collect.cancel()
            client.disconnect()
            coroutineContext.cancelChildren()
        } finally {
            ioScope.cancel()
        }
    }

    /**
     * The issue's original reproducer: a real Ktor WebSocket carrying real
     * encoded frames through [KtorWebSocketFactory].
     */
    @Test
    fun `DATA frames sent over a real WebSocket reach the stream in wire order`() = runBlocking {
        val port = freePort()
        val server = embeddedServer(CIO, port = port) {
            install(ServerWebSockets)
            routing {
                webSocket("/tunnel") {
                    send(Frame.Binary(true, MuxCodec.encode(MuxFrame.Open(STREAM_ID))))
                    for (i in 0 until FRAMES) {
                        val payload = i.toString().toByteArray()
                        send(Frame.Binary(true, MuxCodec.encode(MuxFrame.Data(STREAM_ID, payload))))
                    }
                    for (frame in incoming) {
                        // Keep the session open until the client tears it down.
                    }
                }
            }
        }
        server.start(wait = false)

        val ioScope = CoroutineScope(Dispatchers.IO)
        try {
            val client = TunnelClientImpl(ioScope, KtorWebSocketFactory(ioScope))
            client.connect("ws://localhost:$port/tunnel")

            val streamDeferred = CompletableDeferred<MuxStream>()
            val collect = launch {
                client.incomingSessions.collect { streamDeferred.complete(it) }
            }
            val stream = withTimeout(10_000) { streamDeferred.await() }

            val seen = ArrayList<Int>(FRAMES)
            withTimeout(30_000) {
                repeat(FRAMES) { seen.add(String(stream.read()).toInt()) }
            }

            assertEquals(null, firstDisorder(seen), "mux DATA frames arrived out of order")

            collect.cancel()
            client.disconnect()
            coroutineContext.cancelChildren()
        } finally {
            ioScope.cancel()
            server.stop(0, 0)
        }
    }

    /**
     * Ordering is preserved with a *bounded* queue, so overflow has to mean
     * something. It must never mean "drop a frame": the mux protocol has no
     * sequence numbers, so a dropped frame is indistinguishable from a
     * reordered one downstream. Overflow tears the tunnel down instead.
     */
    @Test
    fun `queue overflow tears the tunnel down rather than dropping frames`() = runBlocking {
        val ioScope = CoroutineScope(Dispatchers.IO)
        try {
            val factory = DirectWebSocketFactory()
            val client = TunnelClientImpl(ioScope, factory)
            client.connect("ws://mux-overflow.invalid/tunnel")

            val streamDeferred = CompletableDeferred<MuxStream>()
            val collect = launch {
                client.incomingSessions.collect { streamDeferred.complete(it) }
            }
            factory.deliver(MuxFrame.Open(STREAM_ID))
            val stream = withTimeout(10_000) { streamDeferred.await() }

            // Nothing ever reads `stream`, so the consumer wedges on the
            // stream's own buffer and the inbound queue backs up. Twice the
            // queue depth overruns queue + stream buffer with margin.
            repeat(TunnelClientImpl.INBOUND_FRAME_QUEUE_CAPACITY * 2) { i ->
                factory.deliver(MuxFrame.Data(STREAM_ID, i.toString().toByteArray()))
            }

            withTimeout(10_000) { client.state.first { it == TunnelState.DISCONNECTED } }
            assertTrue(stream.isClosed, "overflow must tear the stream down")

            collect.cancel()
            coroutineContext.cancelChildren()
        } finally {
            ioScope.cancel()
        }
    }

    /** Describes the first out-of-order element, or null if [seen] is 0..n-1. */
    private fun firstDisorder(seen: List<Int>): String? {
        val bad = seen.indices.firstOrNull { seen[it] != it } ?: return null
        return "index $bad: expected $bad, got ${seen[bad]}"
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /**
     * A [WebSocketFactory] with no transport at all: [deliver] pushes an
     * encoded frame straight into the listener from the calling thread.
     */
    private class DirectWebSocketFactory : WebSocketFactory {
        @Volatile
        private var listener: WebSocketListener? = null

        override fun connect(url: String, listener: WebSocketListener): WebSocketHandle {
            this.listener = listener
            listener.onOpen()
            return NoopHandle
        }

        fun deliver(frame: MuxFrame) {
            val target = checkNotNull(listener) { "connect() has not been called" }
            target.onBinaryMessage(MuxCodec.encode(frame))
        }
    }

    private object NoopHandle : WebSocketHandle {
        override suspend fun sendBinary(data: ByteArray): Boolean = true

        override suspend fun sendText(text: String): Boolean = true

        override suspend fun close(code: Int, reason: String) = Unit
    }
}
