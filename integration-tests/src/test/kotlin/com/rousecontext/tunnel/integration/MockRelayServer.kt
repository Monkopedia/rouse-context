package com.rousecontext.tunnel.integration

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * A mock relay server that speaks the mux protocol over WebSocket.
 *
 * This allows integration testing of the mux client without needing the
 * real Rust relay binary. The mock implements the relay's side of the mux
 * protocol: it can send OPEN, DATA, CLOSE, ERROR frames and receive
 * DATA and CLOSE frames from the client.
 */
class MockRelayServer {
    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null
    val port: Int = findFreePort()

    /** Frames received from the connected client (device). */
    val receivedFrames = CopyOnWriteArrayList<MuxFrame>()

    /** Channel for sending frames to the connected client. */
    val outboundFrames = Channel<MuxFrame>(Channel.BUFFERED)

    /** Signaled when a WebSocket client connects. */
    val clientConnected = CompletableDeferred<Unit>()

    /** Signaled when a WebSocket client disconnects. */
    val clientDisconnected = CompletableDeferred<Unit>()

    /** Channel for received frames (tests can consume these). */
    val inboundChannel = Channel<MuxFrame>(Channel.BUFFERED)

    fun start() {
        server = embeddedServer(CIO, port = port) {
            install(WebSockets)
            routing {
                webSocket("/ws") {
                    clientConnected.complete(Unit)
                    try {
                        val sendJob = launch {
                            for (frame in outboundFrames) {
                                send(Frame.Binary(true, frame.encode()))
                            }
                        }

                        for (wsFrame in incoming) {
                            if (wsFrame is Frame.Binary) {
                                val muxFrame = MuxFrame.decode(wsFrame.readBytes())
                                receivedFrames.add(muxFrame)
                                inboundChannel.send(muxFrame)
                            }
                        }
                        sendJob.cancel()
                    } finally {
                        clientDisconnected.complete(Unit)
                    }
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        outboundFrames.close()
        server?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
    }

    companion object {
        private fun findFreePort(): Int {
            ServerSocket(0).use { return it.localPort }
        }
    }
}
