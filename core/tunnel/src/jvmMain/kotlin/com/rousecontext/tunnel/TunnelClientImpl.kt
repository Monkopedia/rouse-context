package com.rousecontext.tunnel

import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Real implementation of [TunnelClient] that connects to the relay over WebSocket
 * and multiplexes streams using [MuxDemux].
 *
 * Active-stream tracking lives in [MuxDemux] (see its kdoc) rather than in a
 * per-stream wrapper, so peer-initiated CLOSE / ERROR frames flow through the
 * same counter as locally-initiated closes. This is what fixes issue #179's
 * "tunnel stuck in ACTIVE" bug: the ACTIVE -> CONNECTED back-transition now
 * fires whenever the peer closes the last stream, not only when the app
 * explicitly closes one.
 *
 * A [KEEPALIVE_INTERVAL_MS] periodic Ping job is launched once the tunnel is
 * connected. After [KEEPALIVE_MAX_MISSES] consecutive Pings time out, the
 * tunnel is treated as dead and transitions to DISCONNECTED so the service
 * layer can reconnect.
 */
class TunnelClientImpl(
    private val scope: CoroutineScope,
    private val webSocketFactory: WebSocketFactory,
    private val log: (LogLevel, String) -> Unit = { _, _ -> },
    private val stateMachineLog: (LogLevel, String) -> Unit = { _, _ -> },
    private val muxDemuxLog: (LogLevel, String) -> Unit = { _, _ -> },
    private val keepaliveIntervalMillis: Long = KEEPALIVE_INTERVAL_MS,
    private val keepaliveTimeoutMillis: Long = KEEPALIVE_TIMEOUT_MS,
    private val keepaliveMaxMisses: Int = KEEPALIVE_MAX_MISSES
) : TunnelClient {
    private val stateMachine = ConnectionStateMachine(log = stateMachineLog)
    private var muxDemux: MuxDemux? = null
    private var wsHandle: WebSocketHandle? = null
    private var forwardJob: Job? = null
    private var streamCountJob: Job? = null
    private var keepaliveJob: Job? = null

    private val _incomingSessions = Channel<MuxStream>(Channel.BUFFERED)
    private val _errors = MutableSharedFlow<TunnelError>(extraBufferCapacity = 16)

    override val state: StateFlow<TunnelState> = stateMachine.state
    override val incomingSessions: Flow<MuxStream> = _incomingSessions.receiveAsFlow()
    override val errors: SharedFlow<TunnelError> = _errors.asSharedFlow()

    override suspend fun connect(url: String) {
        if (!stateMachine.transition(TunnelState.CONNECTING)) {
            log(
                LogLevel.WARN,
                "TunnelClient: connect() ignored, current state is ${stateMachine.state.value}"
            )
            return
        }
        try {
            val demux = MuxDemux(log = muxDemuxLog)
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
                                log(
                                    LogLevel.DEBUG,
                                    "TunnelClient: frame arrived after disconnect, ignoring"
                                )
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
                    _incomingSessions.send(stream)
                }
            }

            // Track activeStreamCount transitions CONNECTED <-> ACTIVE.
            // The demux increments the counter BEFORE emitting the stream on
            // incomingStreams, so by the time the app sees a stream, state has
            // already flipped to ACTIVE.
            streamCountJob = scope.launch {
                // StateFlow already deduplicates consecutive equal values.
                // Drop the initial 0 so we only react to real changes.
                demux.activeStreamCount
                    .drop(1)
                    .collect { count ->
                        synchronized(stateMachine) {
                            when {
                                count > 0 && stateMachine.state.value == TunnelState.CONNECTED -> {
                                    stateMachine.transition(TunnelState.ACTIVE)
                                }
                                count == 0 && stateMachine.state.value == TunnelState.ACTIVE -> {
                                    stateMachine.transition(TunnelState.CONNECTED)
                                }
                            }
                        }
                    }
            }

            stateMachine.transition(TunnelState.CONNECTED)

            // Start periodic keepalive so a silent half-open socket is detected
            // even when no FCM wake is pending. See issue #179.
            keepaliveJob = scope.launch {
                runKeepaliveLoop(demux)
            }
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

    private suspend fun runKeepaliveLoop(demux: MuxDemux) {
        var misses = 0
        while (scope.isActive && muxDemux === demux) {
            delay(keepaliveIntervalMillis)
            if (muxDemux !== demux) return
            val alive = try {
                demux.sendPingAwaitPong(timeoutMillis = keepaliveTimeoutMillis)
            } catch (_: Exception) {
                false
            }
            if (alive) {
                misses = 0
            } else {
                misses++
                log(
                    LogLevel.WARN,
                    "TunnelClient: keepalive Ping missed ($misses/$keepaliveMaxMisses)"
                )
                if (misses >= keepaliveMaxMisses) {
                    log(
                        LogLevel.WARN,
                        "TunnelClient: keepalive exhausted, treating tunnel as dead"
                    )
                    handleDisconnect(
                        TunnelError.ConnectionFailed(
                            "Keepalive Pings missed $keepaliveMaxMisses times"
                        )
                    )
                    return
                }
            }
        }
    }

    override suspend fun healthCheck(timeout: Duration): Boolean {
        val demux = muxDemux ?: return false
        return try {
            demux.sendPingAwaitPong(timeoutMillis = timeout.inWholeMilliseconds)
        } catch (_: Exception) {
            false
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
        log(LogLevel.INFO, "TunnelClient: disconnected: ${error.message}")
        _errors.emit(error)
        // Use quiet cleanup -- transport is already broken, don't try to send CLOSE frames
        muxDemux?.closeAllQuietly()
        cleanupRefs()
        if (stateMachine.state.value != TunnelState.DISCONNECTED) {
            stateMachine.transition(TunnelState.DISCONNECTED)
        }
    }

    private fun cleanupRefs() {
        keepaliveJob?.cancel()
        keepaliveJob = null
        streamCountJob?.cancel()
        streamCountJob = null
        forwardJob?.cancel()
        forwardJob = null
        wsHandle = null
        // Nulling the demux IS the activeStreamCount reset: the next connect()
        // allocates a fresh MuxDemux whose counter starts at 0. This addresses
        // the "monotonic activeStreamCount across reconnects" bug in #179.
        muxDemux = null
    }

    companion object {
        /** How often to send a Ping when connected. */
        const val KEEPALIVE_INTERVAL_MS = 30_000L

        /** How long to wait for each Pong before counting a miss. */
        const val KEEPALIVE_TIMEOUT_MS = 10_000L

        /** Consecutive missed Pings that mark the tunnel dead. */
        const val KEEPALIVE_MAX_MISSES = 3
    }
}
