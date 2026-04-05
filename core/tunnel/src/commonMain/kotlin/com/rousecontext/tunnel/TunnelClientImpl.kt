package com.rousecontext.tunnel

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Real implementation of [TunnelClient] that connects to the relay over WebSocket
 * and multiplexes streams using [MuxDemux].
 */
class TunnelClientImpl(
    private val scope: CoroutineScope,
    private val httpClient: HttpClient = defaultHttpClient()
) : TunnelClient {
    private val stateMachine = ConnectionStateMachine()
    private var muxDemux: MuxDemux? = null
    private var session: WebSocketSession? = null
    private var receiveJob: Job? = null

    private val _errors = MutableSharedFlow<TunnelError>(extraBufferCapacity = 16)

    override val state: StateFlow<TunnelState> = stateMachine.state
    override val incomingSessions: Flow<MuxStream>
        get() = muxDemux?.incomingStreams ?: MutableSharedFlow()
    override val errors: SharedFlow<TunnelError> = _errors.asSharedFlow()

    override suspend fun connect(url: String) {
        stateMachine.transition(TunnelState.CONNECTING)
        try {
            val wsSession = httpClient.webSocketSession(url)
            val demux = MuxDemux()
            demux.onOutgoingFrame = { frame ->
                wsSession.send(Frame.Binary(true, MuxCodec.encode(frame)))
            }
            muxDemux = demux
            session = wsSession

            receiveJob = scope.launch {
                try {
                    for (frame in wsSession.incoming) {
                        if (frame is Frame.Binary) {
                            val muxFrame = MuxCodec.decode(frame.readBytes())
                            demux.handleFrame(muxFrame)
                        }
                    }
                    // WebSocket closed normally
                    handleDisconnect(TunnelError.WebSocketClosed("WebSocket closed by remote"))
                } catch (e: Exception) {
                    if (isActive) {
                        handleDisconnect(
                            TunnelError.ConnectionFailed("WebSocket error", e)
                        )
                    }
                }
            }

            stateMachine.transition(TunnelState.CONNECTED)
        } catch (e: Exception) {
            stateMachine.transition(TunnelState.DISCONNECTED)
            val error = TunnelError.ConnectionFailed("Failed to connect: ${e.message}", e)
            _errors.emit(error)
            throw error
        }
    }

    override suspend fun disconnect() {
        try {
            muxDemux?.closeAll()
            session?.close()
            receiveJob?.cancelAndJoin()
        } finally {
            cleanup()
            if (stateMachine.state.value != TunnelState.DISCONNECTED) {
                stateMachine.transition(TunnelState.DISCONNECTED)
            }
        }
    }

    private suspend fun handleDisconnect(error: TunnelError) {
        _errors.emit(error)
        cleanup()
        if (stateMachine.state.value != TunnelState.DISCONNECTED) {
            stateMachine.transition(TunnelState.DISCONNECTED)
        }
    }

    private fun cleanup() {
        session = null
        muxDemux = null
        receiveJob = null
    }

    companion object {
        fun defaultHttpClient(): HttpClient = HttpClient {
            install(WebSockets)
        }
    }
}
