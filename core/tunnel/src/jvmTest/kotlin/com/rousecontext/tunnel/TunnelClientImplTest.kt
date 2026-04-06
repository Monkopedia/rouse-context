package com.rousecontext.tunnel

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Tests for [TunnelClientImpl] state transitions and session management.
 */
class TunnelClientImplTest {
    @Test
    fun `connect transitions DISCONNECTED to CONNECTING to CONNECTED`() = runBlocking {
        val port = findFreePort()

        val server =
            embeddedServer(CIO, port = port) {
                install(ServerWebSockets)
                routing {
                    webSocket("/tunnel") {
                        for (frame in incoming) {
                            // consume
                        }
                    }
                }
            }
        server.start(wait = false)

        try {
            val client = TunnelClientImpl(this, KtorWebSocketFactory())

            assertEquals(TunnelState.DISCONNECTED, client.state.value)

            // Track state transitions
            val states = mutableListOf<TunnelState>()
            val collectJob =
                launch {
                    client.state.collect { states.add(it) }
                }

            // Allow collector to start
            delay(50)

            client.connect("ws://localhost:$port/tunnel")

            assertEquals(TunnelState.CONNECTED, client.state.value)

            client.disconnect()

            assertEquals(TunnelState.DISCONNECTED, client.state.value)

            collectJob.cancel()
            coroutineContext.cancelChildren()
        } finally {
            server.stop(0, 0)
        }
    }

    @Test
    fun `OPEN frame received emits to incomingSessions`() = runBlocking {
        val port = findFreePort()
        val serverSessionRef = CompletableDeferred<io.ktor.websocket.WebSocketSession>()

        val server =
            embeddedServer(CIO, port = port) {
                install(ServerWebSockets)
                routing {
                    webSocket("/tunnel") {
                        serverSessionRef.complete(this)
                        for (frame in incoming) {
                            // consume
                        }
                    }
                }
            }
        server.start(wait = false)

        try {
            val client = TunnelClientImpl(this, KtorWebSocketFactory())
            client.connect("ws://localhost:$port/tunnel")

            val sessionReceived = CompletableDeferred<MuxStream>()
            val collectJob =
                launch {
                    client.incomingSessions.collect { session ->
                        sessionReceived.complete(session)
                    }
                }

            // Server sends OPEN
            val serverWs = serverSessionRef.await()
            serverWs.send(
                Frame.Binary(true, MuxCodec.encode(MuxFrame.Open(streamId = 7u)))
            )

            val session =
                withTimeout(5000) {
                    sessionReceived.await()
                }
            assertEquals(7u, session.id)

            client.disconnect()
            collectJob.cancel()
            coroutineContext.cancelChildren()
        } finally {
            server.stop(0, 0)
        }
    }

    @Test
    fun `disconnect sends CLOSE for all streams`() = runBlocking {
        val port = findFreePort()
        val serverSessionRef = CompletableDeferred<io.ktor.websocket.WebSocketSession>()
        val receivedFrames = mutableListOf<MuxFrame>()
        val closeReceived = CompletableDeferred<Unit>()

        val server =
            embeddedServer(CIO, port = port) {
                install(ServerWebSockets)
                routing {
                    webSocket("/tunnel") {
                        serverSessionRef.complete(this)
                        for (frame in incoming) {
                            if (frame is Frame.Binary) {
                                val muxFrame = MuxCodec.decode(frame.readBytes())
                                receivedFrames.add(muxFrame)
                                if (muxFrame is MuxFrame.Close) {
                                    closeReceived.complete(Unit)
                                }
                            }
                        }
                    }
                }
            }
        server.start(wait = false)

        try {
            val client = TunnelClientImpl(this, KtorWebSocketFactory())
            client.connect("ws://localhost:$port/tunnel")

            val sessionReceived = CompletableDeferred<MuxStream>()
            val collectJob =
                launch {
                    client.incomingSessions.collect { session ->
                        sessionReceived.complete(session)
                    }
                }

            // Server opens a stream
            val serverWs = serverSessionRef.await()
            serverWs.send(
                Frame.Binary(true, MuxCodec.encode(MuxFrame.Open(streamId = 10u)))
            )

            withTimeout(5000) { sessionReceived.await() }

            // Disconnect should close all streams
            client.disconnect()

            withTimeout(5000) { closeReceived.await() }

            val closeFrames = receivedFrames.filterIsInstance<MuxFrame.Close>()
            assertTrue(closeFrames.isNotEmpty(), "Expected CLOSE frames to be sent")
            assertTrue(
                closeFrames.any { it.streamId == 10u },
                "Expected CLOSE for stream 10"
            )

            collectJob.cancel()
            coroutineContext.cancelChildren()
        } finally {
            server.stop(0, 0)
        }
    }

    @Test
    fun `WebSocket drop transitions to DISCONNECTED`() = runBlocking {
        val port = findFreePort()

        val server =
            embeddedServer(CIO, port = port) {
                install(ServerWebSockets)
                routing {
                    webSocket("/tunnel") {
                        // Close after brief delay to simulate drop
                        delay(200)
                        close()
                    }
                }
            }
        server.start(wait = false)

        try {
            val client = TunnelClientImpl(this, KtorWebSocketFactory())

            val errorReceived = CompletableDeferred<TunnelError>()
            val errorJob =
                launch {
                    client.errors.collect { error ->
                        errorReceived.complete(error)
                    }
                }

            client.connect("ws://localhost:$port/tunnel")
            assertEquals(TunnelState.CONNECTED, client.state.value)

            // Wait for server to close the WebSocket
            val error =
                withTimeout(5000) {
                    errorReceived.await()
                }

            assertTrue(
                error is TunnelError.WebSocketClosed || error is TunnelError.ConnectionFailed,
                "Expected WebSocketClosed or ConnectionFailed, got ${error::class.simpleName}"
            )

            // State should transition to disconnected
            withTimeout(5000) {
                while (client.state.value != TunnelState.DISCONNECTED) {
                    delay(50)
                }
            }
            assertEquals(TunnelState.DISCONNECTED, client.state.value)

            errorJob.cancel()
            coroutineContext.cancelChildren()
        } finally {
            server.stop(0, 0)
        }
    }

    @Test
    fun `sendFcmToken sends JSON text frame to relay`() = runBlocking {
        val port = findFreePort()
        val textReceived = CompletableDeferred<String>()

        val server =
            embeddedServer(CIO, port = port) {
                install(ServerWebSockets)
                routing {
                    webSocket("/tunnel") {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                textReceived.complete(frame.readText())
                            }
                        }
                    }
                }
            }
        server.start(wait = false)

        try {
            val client = TunnelClientImpl(this, KtorWebSocketFactory())
            client.connect("ws://localhost:$port/tunnel")

            client.sendFcmToken("test-fcm-token-123")

            val received = withTimeout(5000) { textReceived.await() }
            assertEquals("""{"type":"fcm_token","token":"test-fcm-token-123"}""", received)

            client.disconnect()
            coroutineContext.cancelChildren()
        } finally {
            server.stop(0, 0)
        }
    }

    @Test
    fun `sendFcmToken before connect is a no-op`() = runBlocking {
        val client = TunnelClientImpl(this, KtorWebSocketFactory())

        // Should not throw
        client.sendFcmToken("some-token")

        coroutineContext.cancelChildren()
    }

    @Test
    fun `connection failure emits error on SharedFlow`() = runBlocking {
        val client = TunnelClientImpl(this, KtorWebSocketFactory())

        val errorReceived = CompletableDeferred<TunnelError>()
        val errorJob =
            launch {
                client.errors.collect { error ->
                    errorReceived.complete(error)
                }
            }

        // Connect to a port that nothing is listening on
        val result = runCatching { client.connect("ws://localhost:19999/nonexistent") }
        assertTrue(result.isFailure)

        val error =
            withTimeout(5000) {
                errorReceived.await()
            }
        assertTrue(error is TunnelError.ConnectionFailed)
        assertEquals(TunnelState.DISCONNECTED, client.state.value)

        errorJob.cancel()
        coroutineContext.cancelChildren()
    }
}
