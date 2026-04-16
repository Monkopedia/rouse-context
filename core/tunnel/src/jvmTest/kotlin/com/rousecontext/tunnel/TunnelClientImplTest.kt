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
    fun `disconnect when already disconnected does not throw or corrupt state`() = runBlocking {
        val client = TunnelClientImpl(this, KtorWebSocketFactory())

        // Initial state is DISCONNECTED
        assertEquals(TunnelState.DISCONNECTED, client.state.value)

        // Calling disconnect when already disconnected should be a no-op
        client.disconnect()
        assertEquals(TunnelState.DISCONNECTED, client.state.value)

        // Call it again -- still should not throw
        client.disconnect()
        assertEquals(TunnelState.DISCONNECTED, client.state.value)

        coroutineContext.cancelChildren()
    }

    @Test
    fun `connect after disconnect works correctly`() = runBlocking {
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

            // Connect, disconnect, connect again
            client.connect("ws://localhost:$port/tunnel")
            assertEquals(TunnelState.CONNECTED, client.state.value)

            client.disconnect()
            assertEquals(TunnelState.DISCONNECTED, client.state.value)

            // Brief delay for async WebSocket close callbacks to settle.
            // The onClosing/onFailure callbacks fire asynchronously after
            // disconnect() returns, and can race with the next connect().
            delay(200)

            // Second connect should work
            client.connect("ws://localhost:$port/tunnel")
            assertEquals(TunnelState.CONNECTED, client.state.value)

            client.disconnect()
            assertEquals(TunnelState.DISCONNECTED, client.state.value)

            coroutineContext.cancelChildren()
        } finally {
            server.stop(0, 0)
        }
    }

    @Test
    fun `rapid connect-disconnect cycles do not crash or leak state`() = runBlocking {
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

            // Connect/disconnect 5 times with brief settling delays
            repeat(5) { i ->
                client.connect("ws://localhost:$port/tunnel")
                assertEquals(
                    TunnelState.CONNECTED,
                    client.state.value,
                    "Cycle $i: expected CONNECTED after connect"
                )

                client.disconnect()
                assertEquals(
                    TunnelState.DISCONNECTED,
                    client.state.value,
                    "Cycle $i: expected DISCONNECTED after disconnect"
                )

                // Allow async WebSocket close callbacks to settle
                delay(200)
            }

            // Final state should be DISCONNECTED
            assertEquals(TunnelState.DISCONNECTED, client.state.value)

            coroutineContext.cancelChildren()
        } finally {
            server.stop(0, 0)
        }
    }

    @Test
    fun `connection failure invokes log lambda`() = runBlocking {
        val captured = mutableListOf<Pair<LogLevel, String>>()
        val client = TunnelClientImpl(
            this,
            KtorWebSocketFactory(),
            log = { level, msg -> captured.add(level to msg) }
        )

        // Connect to a port that nothing is listening on -- triggers handleDisconnect
        val result = runCatching { client.connect("ws://localhost:19999/nonexistent") }
        assertTrue(result.isFailure)

        // Wait briefly for async onFailure callback to fire
        delay(200)

        assertTrue(
            captured.any {
                it.first == LogLevel.INFO &&
                    it.second.startsWith("TunnelClient: disconnected:")
            },
            "Expected INFO log starting with 'TunnelClient: disconnected:', got $captured"
        )

        coroutineContext.cancelChildren()
    }

    @Test
    fun `periodic keepalive detects dead tunnel and flips to DISCONNECTED`() = runBlocking {
        val port = findFreePort()

        val server = embeddedServer(CIO, port = port) {
            install(ServerWebSockets)
            routing {
                webSocket("/tunnel") {
                    // Accept all frames silently -- no Pong ever emitted, so the
                    // keepalive loop should exhaust its miss budget and
                    // disconnect the tunnel.
                    for (frame in incoming) { /* consume */ }
                }
            }
        }
        server.start(wait = false)

        try {
            val client = TunnelClientImpl(
                scope = this,
                webSocketFactory = KtorWebSocketFactory(),
                // Aggressive timing so the test doesn't wait 90s.
                keepaliveIntervalMillis = 50L,
                keepaliveTimeoutMillis = 50L,
                keepaliveMaxMisses = 3
            )
            client.connect("ws://localhost:$port/tunnel")
            assertEquals(TunnelState.CONNECTED, client.state.value)

            // Wait for the keepalive loop to exhaust its budget and flip state.
            withTimeout(10_000) {
                while (client.state.value != TunnelState.DISCONNECTED) {
                    delay(25)
                }
            }
            assertEquals(TunnelState.DISCONNECTED, client.state.value)

            coroutineContext.cancelChildren()
        } finally {
            server.stop(0, 0)
        }
    }

    @Test
    fun `healthCheck returns true for a live tunnel that echoes Pong`() = runBlocking {
        val port = findFreePort()

        val server = embeddedServer(CIO, port = port) {
            install(ServerWebSockets)
            routing {
                webSocket("/tunnel") {
                    for (frame in incoming) {
                        if (frame is Frame.Binary) {
                            val muxFrame = MuxCodec.decode(frame.readBytes())
                            if (muxFrame is MuxFrame.Ping) {
                                // Relay must echo Pong
                                send(
                                    Frame.Binary(
                                        true,
                                        MuxCodec.encode(MuxFrame.Pong(muxFrame.nonce))
                                    )
                                )
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

            val live = client.healthCheck(kotlin.time.Duration.parse("2s"))
            assertTrue(live, "healthCheck should return true against a responsive peer")

            client.disconnect()
            coroutineContext.cancelChildren()
        } finally {
            server.stop(0, 0)
        }
    }

    @Test
    fun `healthCheck returns false when peer does not respond to Ping`() = runBlocking {
        val port = findFreePort()

        val server = embeddedServer(CIO, port = port) {
            install(ServerWebSockets)
            routing {
                webSocket("/tunnel") {
                    for (frame in incoming) {
                        // Silently drop frames -- simulates a half-open socket
                        // where the peer never produces output.
                    }
                }
            }
        }
        server.start(wait = false)

        try {
            val client = TunnelClientImpl(this, KtorWebSocketFactory())
            client.connect("ws://localhost:$port/tunnel")

            val live = client.healthCheck(kotlin.time.Duration.parse("0.3s"))
            assertTrue(!live, "healthCheck should return false when peer does not echo Pong")

            client.disconnect()
            coroutineContext.cancelChildren()
        } finally {
            server.stop(0, 0)
        }
    }

    @Test
    fun `healthCheck returns false when not connected`() = runBlocking {
        val client = TunnelClientImpl(this, KtorWebSocketFactory())
        // No connect() called.
        val live = client.healthCheck(kotlin.time.Duration.parse("0.2s"))
        assertTrue(!live, "healthCheck must not claim healthy when never connected")
        coroutineContext.cancelChildren()
    }

    @Test
    fun `peer Close on active stream transitions ACTIVE back to CONNECTED`() = runBlocking {
        val port = findFreePort()
        val serverSessionRef = CompletableDeferred<io.ktor.websocket.WebSocketSession>()

        val server = embeddedServer(CIO, port = port) {
            install(ServerWebSockets)
            routing {
                webSocket("/tunnel") {
                    serverSessionRef.complete(this)
                    for (frame in incoming) { /* consume */ }
                }
            }
        }
        server.start(wait = false)

        try {
            val client = TunnelClientImpl(this, KtorWebSocketFactory())
            client.connect("ws://localhost:$port/tunnel")
            assertEquals(TunnelState.CONNECTED, client.state.value)

            val sessionReceived = CompletableDeferred<MuxStream>()
            val collectJob = launch {
                client.incomingSessions.collect { s ->
                    if (!sessionReceived.isCompleted) sessionReceived.complete(s)
                }
            }

            val serverWs = serverSessionRef.await()
            serverWs.send(Frame.Binary(true, MuxCodec.encode(MuxFrame.Open(streamId = 1u))))

            withTimeout(5_000) { sessionReceived.await() }

            // Wait for the ACTIVE transition
            withTimeout(5_000) {
                while (client.state.value != TunnelState.ACTIVE) delay(20)
            }
            assertEquals(TunnelState.ACTIVE, client.state.value)

            // Server sends CLOSE -- must flip back to CONNECTED via the demux path.
            serverWs.send(Frame.Binary(true, MuxCodec.encode(MuxFrame.Close(streamId = 1u))))

            withTimeout(5_000) {
                while (client.state.value != TunnelState.CONNECTED) delay(20)
            }
            assertEquals(TunnelState.CONNECTED, client.state.value)

            client.disconnect()
            collectJob.cancel()
            coroutineContext.cancelChildren()
        } finally {
            server.stop(0, 0)
        }
    }

    @Suppress("LongMethod")
    @Test
    fun `reconnect after ACTIVE resets activeStreamCount`() = runBlocking {
        val port = findFreePort()
        val serverSessionRef = java.util.concurrent.atomic.AtomicReference<
            io.ktor.websocket.WebSocketSession
            >()
        val newSession = kotlinx.coroutines.channels.Channel<io.ktor.websocket.WebSocketSession>(
            kotlinx.coroutines.channels.Channel.BUFFERED
        )

        val server = embeddedServer(CIO, port = port) {
            install(ServerWebSockets)
            routing {
                webSocket("/tunnel") {
                    serverSessionRef.set(this)
                    newSession.send(this)
                    for (frame in incoming) { /* consume */ }
                }
            }
        }
        server.start(wait = false)

        try {
            val client = TunnelClientImpl(this, KtorWebSocketFactory())

            // First connect: open a stream so state reaches ACTIVE, then drop.
            client.connect("ws://localhost:$port/tunnel")
            val sessionReceived1 = CompletableDeferred<MuxStream>()
            val collectJob1 = launch {
                client.incomingSessions.collect { s ->
                    if (!sessionReceived1.isCompleted) sessionReceived1.complete(s)
                }
            }

            val ws1 = newSession.receive()
            ws1.send(Frame.Binary(true, MuxCodec.encode(MuxFrame.Open(streamId = 5u))))
            withTimeout(5_000) { sessionReceived1.await() }

            withTimeout(5_000) {
                while (client.state.value != TunnelState.ACTIVE) delay(20)
            }

            // Disconnect (stream count remains 1 internally until cleanup)
            client.disconnect()
            assertEquals(TunnelState.DISCONNECTED, client.state.value)
            collectJob1.cancel()

            delay(200)

            // Second connect: a fresh stream arrives. State must reach ACTIVE,
            // which requires activeStreamCount to have been reset to 0 first.
            client.connect("ws://localhost:$port/tunnel")
            assertEquals(TunnelState.CONNECTED, client.state.value)

            val sessionReceived2 = CompletableDeferred<MuxStream>()
            val collectJob2 = launch {
                client.incomingSessions.collect { s ->
                    if (!sessionReceived2.isCompleted) sessionReceived2.complete(s)
                }
            }

            val ws2 = newSession.receive()
            ws2.send(Frame.Binary(true, MuxCodec.encode(MuxFrame.Open(streamId = 11u))))
            withTimeout(5_000) { sessionReceived2.await() }

            withTimeout(5_000) {
                while (client.state.value != TunnelState.ACTIVE) delay(20)
            }
            assertEquals(TunnelState.ACTIVE, client.state.value)

            client.disconnect()
            collectJob2.cancel()
            coroutineContext.cancelChildren()
        } finally {
            server.stop(0, 0)
        }
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
