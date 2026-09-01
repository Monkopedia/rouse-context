package com.rousecontext.tunnel

import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
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
 *
 * ## Inbound frame ordering (issue #562)
 *
 * Inbound frames are decoded on the WebSocket callback and pushed onto a single
 * bounded queue drained by exactly one consumer coroutine. Ordering is
 * therefore a property of *this class*, not of whichever dispatcher backs
 * [scope].
 *
 * This used to be `scope.launch { demux.handleFrame(frame) }` per message, with
 * nothing serialising the launches. That was correct only by accident: the app
 * injects a `Dispatchers.Main` scope, whose single event-loop thread happened to
 * run the launches FIFO. Under any multi-threaded scope the coroutines raced to
 * `MuxStreamImpl.receiveData`, and DATA frames reached the stream out of wire
 * order. The mux protocol carries no sequence numbers, so nothing downstream can
 * repair that: a swapped pair of TLS records fails `SSLEngine.unwrap` and the
 * bridge treats the failure as EOF, leaving a silently dead tunnel.
 *
 * Ordering is global rather than per-stream. The protocol only requires
 * per-stream ordering, but every mux stream rides one WebSocket over one TCP
 * connection, so a stalled stream already stalls its peers at the socket layer
 * once the receive window closes. Per-stream queues would buy decoupling the
 * transport does not actually provide, at the cost of a queue and a coroutine
 * per stream; a single consumer makes the pre-existing head-of-line coupling
 * explicit and bounded.
 *
 * Neither a `Mutex` around `handleFrame` nor a `limitedParallelism(1)`
 * dispatcher would fix this. A mutex serialises *execution*, not *arrival*: the
 * per-frame coroutines still race to reach the lock, and whichever the scheduler
 * runs first wins. `limitedParallelism(1)` caps parallelism, not concurrency —
 * the moment `handleFrame` suspends (e.g. on a full stream buffer) the next
 * frame starts and the two interleave.
 *
 * The consumer runs on [Dispatchers.Default], not on whatever backs [scope]
 * (issue #569). Since it is now the *only* thing demultiplexing frames,
 * inheriting the app's `Dispatchers.Main` scope would put the entire tunnel
 * behind the main thread: a slow recomposition would stall frame handling, and
 * a long enough stall would overflow the queue below and report it as a stalled
 * stream reader — naming the wrong subsystem. Ordering is unaffected, because
 * it comes from there being one consumer rather than from the dispatcher being
 * single-threaded.
 *
 * The cost of one consumer is that it is a single point of failure (issue
 * #568): with a coroutine per frame a throw cost one frame, but the lone
 * consumer dying costs every frame that follows it. So both ways the consumer
 * can stop — a full queue and an unexpected throw out of `handleFrame` — are
 * treated as tunnel-level failures that transition to DISCONNECTED and emit a
 * named [TunnelError]. Neither may end in a quiet CONNECTED-but-deaf tunnel.
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

    /** Ordered inbound frame queue for the current connection. See class kdoc. */
    private var inboundFrames: Channel<MuxFrame>? = null

    /** The single coroutine draining [inboundFrames]. */
    private var inboundJob: Job? = null

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
        // Declared outside the try so the failure paths below can close it.
        val inbound = Channel<MuxFrame>(INBOUND_FRAME_QUEUE_CAPACITY)
        inboundFrames = inbound
        try {
            val demux = MuxDemux(log = muxDemuxLog)
            val opened = CompletableDeferred<Unit>()

            val handle = webSocketFactory.connect(url, transportListener(inbound, opened))

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
                        // transition() is internally synchronized (#269), so the
                        // read-check-call pattern here is safe: a racing
                        // disconnect() that invalidates the read will cause the
                        // transition() below to be rejected as invalid and
                        // logged, which is the correct outcome.
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

            stateMachine.transition(TunnelState.CONNECTED)

            // THE serialisation point: exactly one coroutine ever calls
            // demux.handleFrame, so frames are handled in the order the
            // WebSocket reader delivered them. Started last on purpose --
            // frames that arrive earlier simply wait in `inbound`, which means
            // onOutgoingFrame is wired (a handshake-time PING is echoed), the
            // stream forwarder and the ACTIVE-count collector are already
            // subscribed, and no OPEN can be handled before the tunnel has
            // reached CONNECTED.
            // Dispatchers.Default rather than the injected scope's dispatcher:
            // the app injects a Dispatchers.Main scope, which would put every
            // inbound frame in the app behind whatever else occupies the main
            // thread. See the class kdoc.
            inboundJob = scope.launch(Dispatchers.Default) { consumeInboundFrames(inbound, demux) }

            // Start periodic keepalive so a silent half-open socket is detected
            // even when no FCM wake is pending. See issue #179.
            keepaliveJob = scope.launch {
                runKeepaliveLoop(demux)
            }
        } catch (e: CancellationException) {
            // MUST stay above both clauses below -- see [abandonConnect] (#644).
            abandonConnect(inbound)
            throw e
        } catch (e: TunnelError) {
            abandonConnect(inbound)
            _errors.emit(e)
            throw e
        } catch (e: Exception) {
            abandonConnect(inbound)
            val error = TunnelError.ConnectionFailed("Failed to connect: ${e.message}", e)
            _errors.emit(error)
            throw error
        }
    }

    /**
     * Unwind a half-built connection: close the inbound queue and reflect that
     * the client is not connected.
     *
     * Shared by all three failure paths out of [connect], including the
     * cancellation one. Cancellation IS an `Exception` on the JVM
     * (`java.util.concurrent.CancellationException` extends
     * `IllegalStateException`), so before #644 a scope teardown while
     * `opened.await()` was suspended fell into the broad `catch` and came back
     * as a [TunnelError.ConnectionFailed] -- thrown to
     * `TunnelForegroundService`, whose untyped catch calls
     * `crashReporter.logCaughtException`, and additionally published on
     * [errors]. Neither is true of a cancelled connect: the caller went away,
     * the connection did not fail. Same defect as `TlsAcceptor.accept`.
     *
     * The crash report is the consequence that actually lands. The [errors]
     * publication is not a user-visible surface today: that `SharedFlow` has
     * no production collector anywhere in the repo -- measured against
     * `incomingSessions`, which has three, so the empty result is a real read
     * rather than a broken search.
     *
     * The cancellation path still performs this cleanup -- neither call
     * suspends, so both are safe in a cancelled coroutine, and leaving the
     * queue open or the state at `CONNECTING` would strand the client. Only the
     * error *report* is dropped.
     */
    private fun abandonConnect(inbound: Channel<MuxFrame>) {
        closeInboundQueue(inbound)
        stateMachine.transition(TunnelState.DISCONNECTED)
    }

    /**
     * Build the transport callback for one connection attempt.
     *
     * [inbound] is the connection's ordered frame queue and [opened] is
     * completed by the handshake so `connect` can wait on it.
     */
    private fun transportListener(
        inbound: Channel<MuxFrame>,
        opened: CompletableDeferred<Unit>
    ): WebSocketListener = object : WebSocketListener {
        override fun onOpen() {
            opened.complete(Unit)
        }

        override fun onBinaryMessage(data: ByteArray) {
            // Decode here (cheap, and keeps malformed frames on the reader's
            // error path), then hand off to the single consumer coroutine.
            // Do NOT launch a coroutine per frame -- see the class kdoc.
            enqueueInbound(inbound, MuxCodec.decode(data))
        }

        override fun onClosing(code: Int, reason: String) {
            // If the server closes before onOpen ever fires, the pre-CONNECTED
            // awaiter on `opened.await()` would hang until the surrounding
            // scope is cancelled. Surface the close as a connect failure on
            // that path. See #420.
            if (!opened.isCompleted) {
                opened.completeExceptionally(
                    TunnelError.WebSocketClosed(
                        "WebSocket closed during handshake: $code $reason"
                    )
                )
            }
            scope.launch {
                handleDisconnect(
                    TunnelError.WebSocketClosed("WebSocket closed by remote: $code $reason")
                )
            }
        }

        override fun onFailure(error: Throwable) {
            opened.completeExceptionally(error)
            scope.launch {
                handleDisconnect(TunnelError.ConnectionFailed("WebSocket error", error))
            }
        }
    }

    /**
     * Push a decoded frame onto the ordered inbound queue.
     *
     * Called from the WebSocket reader, which is not a coroutine on every
     * transport (OkHttp delivers on its own reader thread), so this cannot
     * suspend to apply backpressure. The queue is bounded anyway: buying
     * ordering with an unbounded buffer would just trade a reordering bug for
     * an OOM. A full queue means the consumer is wedged behind a stream whose
     * reader has stalled; since the mux protocol has no sequence numbers,
     * dropping the frame would silently corrupt that stream, so the tunnel is
     * torn down with a named error instead and the service layer reconnects.
     */
    private fun enqueueInbound(queue: Channel<MuxFrame>, frame: MuxFrame) {
        val result = queue.trySend(frame)
        if (result.isSuccess) return
        if (result.isClosed) {
            log(LogLevel.DEBUG, "TunnelClient: frame arrived after disconnect, ignoring")
            return
        }
        log(
            LogLevel.ERROR,
            "TunnelClient: inbound frame queue full ($INBOUND_FRAME_QUEUE_CAPACITY), " +
                "a stream reader has stalled; tearing down the tunnel"
        )
        queue.close()
        scope.launch {
            handleDisconnect(
                TunnelError.ConnectionFailed(
                    "Inbound mux frame queue overflowed at " +
                        "$INBOUND_FRAME_QUEUE_CAPACITY frames; a stream reader stalled"
                )
            )
        }
    }

    /**
     * The single consumer of [queue]. Being the *only* caller of
     * [MuxDemux.handleFrame] is what preserves wire order.
     */
    private suspend fun consumeInboundFrames(queue: Channel<MuxFrame>, demux: MuxDemux) {
        try {
            for (frame in queue) {
                try {
                    demux.handleFrame(frame)
                } catch (_: ClosedSendChannelException) {
                    // Stream (or the demux) was torn down while this frame was
                    // in flight. Same tolerance the per-frame launches had.
                    log(LogLevel.DEBUG, "TunnelClient: frame arrived after disconnect, ignoring")
                } catch (e: CancellationException) {
                    // Cooperative cancellation, never a failure. MUST stay above
                    // the broad catch below: CancellationException is an
                    // Exception, so catching Exception without this clause would
                    // swallow every teardown and report it as a tunnel error.
                    throw e
                } catch (e: Exception) {
                    failInboundConsumer(queue, frame, e)
                    return
                }
            }
        } finally {
            // On cancellation or normal completion nothing will drain this queue
            // again, so close it: producers then take the isClosed branch above
            // instead of filling a queue nobody reads.
            queue.close()
        }
    }

    /**
     * Tear the tunnel down after [MuxDemux.handleFrame] threw something
     * unexpected. See issue #568.
     *
     * Because [consumeInboundFrames] is the only coroutine that will ever call
     * `handleFrame`, an escaping throw is a *tunnel-level* failure, not a
     * per-frame one: letting it kill the consumer leaves the socket up and the
     * state machine on CONNECTED while every later frame goes nowhere — no
     * transition, no error, nothing to trigger a reconnect. That is
     * indistinguishable from the #558 symptom.
     *
     * Deliberately the same shape as the overflow path in [enqueueInbound]:
     * close the queue so the reader stops filling it, then hand the teardown to
     * a sibling coroutine. The teardown must not run inline — [handleDisconnect]
     * cancels this very job via `cleanupRefs`.
     */
    private fun failInboundConsumer(queue: Channel<MuxFrame>, frame: MuxFrame, cause: Exception) {
        val frameName = frame::class.simpleName
        log(
            LogLevel.ERROR,
            "TunnelClient: inbound frame handler threw on $frameName " +
                "(${cause::class.simpleName}: ${cause.message}); tearing down the tunnel"
        )
        queue.close()
        scope.launch {
            handleDisconnect(
                TunnelError.ConnectionFailed(
                    "Inbound mux frame handler threw on $frameName",
                    cause
                )
            )
        }
    }

    private fun closeInboundQueue(queue: Channel<MuxFrame>) {
        queue.close()
        if (inboundFrames === queue) inboundFrames = null
    }

    private suspend fun runKeepaliveLoop(demux: MuxDemux) {
        var misses = 0
        while (scope.isActive && muxDemux === demux) {
            delay(keepaliveIntervalMillis)
            if (muxDemux !== demux) return
            val alive = try {
                demux.sendPingAwaitPong(timeoutMillis = keepaliveTimeoutMillis)
            } catch (e: CancellationException) {
                // MUST stay above the broad catch (issue #646). Counting a
                // cancelled Ping as a miss lets an ordinary teardown accumulate
                // into a synthesised ConnectionFailed("Keepalive Pings missed
                // N times"). Only reachable because MuxDemux.sendPingAwaitPong
                // now propagates instead of returning false.
                throw e
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

    /**
     * A cancelled health check propagates rather than reporting a dead tunnel
     * (issue #646). `WakeReconnectDecider` reacts to `false` by calling
     * `disconnect()` and `connect()` -- work performed after cancellation --
     * and the cancellation rethrow #650 added there could not fire while this
     * frame (and `MuxDemux.sendPingAwaitPong` below it) swallowed cancellation
     * into `false` first.
     */
    override suspend fun healthCheck(timeout: Duration): Boolean {
        val demux = muxDemux ?: return false
        return try {
            demux.sendPingAwaitPong(timeoutMillis = timeout.inWholeMilliseconds)
        } catch (e: CancellationException) {
            // MUST stay above the broad catch: CancellationException is an
            // Exception on the JVM.
            throw e
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun sendFcmToken(token: String) {
        val handle = wsHandle ?: return
        val json = """{"type":"fcm_token","token":"$token"}"""
        handle.sendText(json)
    }

    override suspend fun sendPushEndpoint(kind: String, value: String) {
        val handle = wsHandle ?: return
        val json = """{"type":"push_endpoint","kind":"$kind","value":"$value"}"""
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
        // Close the queue before cancelling its consumer so any frame the
        // reader pushes in the interim takes the "after disconnect" branch
        // rather than accumulating in a queue nobody drains.
        inboundFrames?.close()
        inboundFrames = null
        inboundJob?.cancel()
        inboundJob = null
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
        /**
         * Depth of the ordered inbound frame queue.
         *
         * Only fills when the consumer is blocked handing DATA to a stream
         * whose reader has stalled, so it is sized well above a single
         * stream's own buffer (`Channel.BUFFERED`, 64) while keeping the worst
         * case bounded. Overflow tears the tunnel down rather than dropping a
         * frame -- see [enqueueInbound].
         */
        const val INBOUND_FRAME_QUEUE_CAPACITY = 512

        /** How often to send a Ping when connected. */
        const val KEEPALIVE_INTERVAL_MS = 30_000L

        /** How long to wait for each Pong before counting a miss. */
        const val KEEPALIVE_TIMEOUT_MS = 10_000L

        /** Consecutive missed Pings that mark the tunnel dead. */
        const val KEEPALIVE_MAX_MISSES = 3
    }
}
