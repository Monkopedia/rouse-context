package com.rousecontext.tunnel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Real implementation of [TunnelClient] that connects to the relay over WebSocket
 * and multiplexes streams using [MuxDemux].
 */
class TunnelClientImpl(
    private val scope: CoroutineScope,
    private val webSocketFactory: WebSocketFactory
) : TunnelClient {
    private val stateMachine = ConnectionStateMachine()
    private var muxDemux: MuxDemux? = null
    private var wsHandle: WebSocketHandle? = null
    private var forwardJob: Job? = null
    @Volatile
    private var activeStreamCount = 0

    private val _incomingSessions = Channel<MuxStream>(Channel.BUFFERED)
    private val _errors = MutableSharedFlow<TunnelError>(extraBufferCapacity = 16)

    override val state: StateFlow<TunnelState> = stateMachine.state
    override val incomingSessions: Flow<MuxStream> = _incomingSessions.receiveAsFlow()
    override val errors: SharedFlow<TunnelError> = _errors.asSharedFlow()

    override suspend fun connect(url: String) {
        stateMachine.transition(TunnelState.CONNECTING)
        try {
            val demux = MuxDemux()
            val opened = CompletableDeferred<Unit>()

            val handle = webSocketFactory.connect(
                url,
                object : WebSocketListener {
                    override fun onOpen() {
                        opened.complete(Unit)
                    }

                    override fun onBinaryMessage(data: ByteArray) {
                        val muxFrame = MuxCodec.decode(data)
                        scope.launch {
                            try {
                                demux.handleFrame(muxFrame)
                            } catch (_: kotlinx.coroutines.channels.ClosedSendChannelException) {
                                println("TunnelClient: frame arrived after disconnect, ignoring")
                            }
                        }
                    }

                    override fun onClosing(code: Int, reason: String) {
                        scope.launch {
                            handleDisconnect(
                                TunnelError.WebSocketClosed(
                                    "WebSocket closed by remote: $code $reason"
                                )
                            )
                        }
                    }

                    override fun onFailure(error: Throwable) {
                        opened.completeExceptionally(error)
                        scope.launch {
                            handleDisconnect(
                                TunnelError.ConnectionFailed("WebSocket error", error)
                            )
                        }
                    }
                }
            )

            // Wait for the WebSocket handshake to complete
            opened.await()

            demux.onOutgoingFrame = { frame ->
                handle.sendBinary(MuxCodec.encode(frame))
            }

            muxDemux = demux
            wsHandle = handle
            forwardJob = scope.launch {
                demux.incomingStreams.collect { stream ->
                    synchronized(stateMachine) {
                        activeStreamCount++
                        if (activeStreamCount == 1 &&
                            stateMachine.state.value == TunnelState.CONNECTED
                        ) {
                            stateMachine.transition(TunnelState.ACTIVE)
                        }
                    }
                    val wrapped = StreamCloseTracker(stream) {
                        synchronized(stateMachine) {
                            activeStreamCount--
                            if (activeStreamCount == 0 &&
                                stateMachine.state.value == TunnelState.ACTIVE
                            ) {
                                stateMachine.transition(TunnelState.CONNECTED)
                            }
                        }
                    }
                    _incomingSessions.send(wrapped)
                }
            }

            stateMachine.transition(TunnelState.CONNECTED)
        } catch (e: TunnelError) {
            stateMachine.transition(TunnelState.DISCONNECTED)
            _errors.emit(e)
            throw e
        } catch (e: Exception) {
            stateMachine.transition(TunnelState.DISCONNECTED)
            val error = TunnelError.ConnectionFailed("Failed to connect: ${e.message}", e)
            _errors.emit(error)
            throw error
        }
    }

    override suspend fun sendFcmToken(token: String) {
        val handle = wsHandle ?: return
        val json = """{"type":"fcm_token","token":"$token"}"""
        handle.sendText(json)
    }

    override suspend fun disconnect() {
        try {
            muxDemux?.closeAll()
            wsHandle?.close()
        } finally {
            cleanupRefs()
            if (stateMachine.state.value != TunnelState.DISCONNECTED) {
                stateMachine.transition(TunnelState.DISCONNECTED)
            }
        }
    }

    private suspend fun handleDisconnect(error: TunnelError) {
        println("TunnelClient: disconnected: ${error.message}")
        _errors.emit(error)
        // Use quiet cleanup -- transport is already broken, don't try to send CLOSE frames
        muxDemux?.closeAllQuietly()
        cleanupRefs()
        if (stateMachine.state.value != TunnelState.DISCONNECTED) {
            stateMachine.transition(TunnelState.DISCONNECTED)
        }
    }

    private fun cleanupRefs() {
        forwardJob?.cancel()
        forwardJob = null
        wsHandle = null
        muxDemux = null
    }
}
