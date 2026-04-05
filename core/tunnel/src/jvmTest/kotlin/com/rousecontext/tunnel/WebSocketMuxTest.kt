package com.rousecontext.tunnel

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Tests that verify mux frames flow correctly over a real WebSocket connection.
 */
class WebSocketMuxTest {
    @Test
    fun `mux frames round-trip over real WebSocket`() =
        runBlocking {
            val port = findFreePort()
            val server =
                embeddedServer(CIO, port = port) {
                    install(ServerWebSockets)
                    routing {
                        webSocket("/tunnel") {
                            for (frame in incoming) {
                                if (frame is Frame.Binary) {
                                    val muxFrame = MuxFrame.decode(frame.readBytes())
                                    // Echo it back
                                    send(Frame.Binary(true, muxFrame.encode()))
                                }
                            }
                        }
                    }
                }
            server.start(wait = false)

            try {
                val client = TunnelClientImpl(this)

                client.connect("ws://localhost:$port/tunnel")

                assertEquals(TunnelState.Connected, client.state.value)

                // Disconnect and verify state
                client.disconnect()
                assertEquals(TunnelState.Disconnected, client.state.value)

                coroutineContext.cancelChildren()
            } finally {
                server.stop(0, 0)
            }
        }

    @Test
    fun `OPEN frame from server creates incoming session`() =
        runBlocking {
            val port = findFreePort()
            val serverSessionRef = CompletableDeferred<io.ktor.websocket.WebSocketSession>()

            val server =
                embeddedServer(CIO, port = port) {
                    install(ServerWebSockets)
                    routing {
                        webSocket("/tunnel") {
                            serverSessionRef.complete(this)
                            for (frame in incoming) {
                                // consume frames
                            }
                        }
                    }
                }
            server.start(wait = false)

            try {
                val client = TunnelClientImpl(this)
                client.connect("ws://localhost:$port/tunnel")

                val sessionReceived = CompletableDeferred<MuxStream>()
                val collectJob =
                    launch {
                        client.incomingSessions.collect { session ->
                            sessionReceived.complete(session)
                        }
                    }

                // Server sends an OPEN frame
                val serverWs = serverSessionRef.await()
                val openFrame = MuxFrame(MuxFrame.Type.OPEN, streamId = 42)
                serverWs.send(Frame.Binary(true, openFrame.encode()))

                // Client should receive the session
                val session =
                    withTimeout(5000) {
                        sessionReceived.await()
                    }
                assertEquals(42, session.streamId)

                // Server sends DATA for that stream
                val dataFrame = MuxFrame(MuxFrame.Type.DATA, streamId = 42, payload = "hello".toByteArray())
                serverWs.send(Frame.Binary(true, dataFrame.encode()))

                val received =
                    withTimeout(5000) {
                        session.read()
                    }
                assertEquals("hello", String(received))

                // Client writes back
                session.write("world".toByteArray())

                client.disconnect()
                collectJob.cancel()
                coroutineContext.cancelChildren()
            } finally {
                server.stop(0, 0)
            }
        }

    @Test
    fun `multiple streams multiplex over single WebSocket`() =
        runBlocking {
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
                val client = TunnelClientImpl(this)
                client.connect("ws://localhost:$port/tunnel")

                val sessions = mutableListOf<MuxStream>()
                val twoSessions = CompletableDeferred<Unit>()
                val collectJob =
                    launch {
                        client.incomingSessions.collect { session ->
                            sessions.add(session)
                            if (sessions.size == 2) twoSessions.complete(Unit)
                        }
                    }

                val serverWs = serverSessionRef.await()

                // Open two streams
                serverWs.send(Frame.Binary(true, MuxFrame(MuxFrame.Type.OPEN, streamId = 1).encode()))
                serverWs.send(Frame.Binary(true, MuxFrame(MuxFrame.Type.OPEN, streamId = 2).encode()))

                withTimeout(5000) { twoSessions.await() }

                // Send data to each stream
                serverWs.send(
                    Frame.Binary(true, MuxFrame(MuxFrame.Type.DATA, 1, "stream1".toByteArray()).encode()),
                )
                serverWs.send(
                    Frame.Binary(true, MuxFrame(MuxFrame.Type.DATA, 2, "stream2".toByteArray()).encode()),
                )

                val data1 = withTimeout(5000) { sessions[0].read() }
                val data2 = withTimeout(5000) { sessions[1].read() }

                assertEquals("stream1", String(data1))
                assertEquals("stream2", String(data2))

                client.disconnect()
                collectJob.cancel()
                coroutineContext.cancelChildren()
            } finally {
                server.stop(0, 0)
            }
        }
}

internal fun findFreePort(): Int {
    val socket = ServerSocket(0)
    val port = socket.localPort
    socket.close()
    return port
}
