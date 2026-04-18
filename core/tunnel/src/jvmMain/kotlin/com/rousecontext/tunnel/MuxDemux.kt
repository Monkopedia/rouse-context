package com.rousecontext.tunnel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Multiplexer/demultiplexer that manages streams over a single connection.
 *
 * Accepts raw frames from the wire, routes DATA to the correct stream,
 * handles OPEN/CLOSE/ERROR lifecycle, echoes PING as PONG, and provides a
 * flow of new streams plus a [StateFlow] of active stream counts.
 *
 * The [activeStreamCount] used to live in `TunnelClientImpl` via a per-stream
 * wrapper (`StreamCloseTracker`) that counted only local `stream.close()`
 * calls. This missed peer-initiated `MuxFrame.Close` frames, which kept the
 * tunnel stuck in ACTIVE forever under half-open-socket conditions (see
 * issue #179). The counter lives here now so every open and every close —
 * local, peer, or error-driven — is accounted for in one place.
 *
 * Thread-safe: all access to the stream map is synchronized via a [Mutex].
 */
class MuxDemux(private val log: (LogLevel, String) -> Unit = { _, _ -> }) {

    private val streams = mutableMapOf<UInt, MuxStreamImpl>()
    private val streamsMutex = Mutex()
    private val incomingChannel = Channel<MuxStreamImpl>(Channel.BUFFERED)

    private val _activeStreamCount = MutableStateFlow(0)

    /** Number of currently open mux streams (open frames received minus closes). */
    val activeStreamCount: StateFlow<Int> get() = _activeStreamCount.asStateFlow()

    /**
     * Flow of newly opened streams (from remote OPEN frames).
     */
    val incomingStreams: Flow<MuxStreamImpl>
        get() = incomingChannel.receiveAsFlow()

    /**
     * Maximum number of concurrent streams. OPEN frames beyond this limit
     * are rejected with an ERROR frame.
     */
    var maxStreams: Int = MAX_CONCURRENT_STREAMS

    /**
     * Pending Ping waiters keyed by nonce. Populated by [sendPingAwaitPong];
     * resolved by incoming PONG frames whose nonce matches.
     */
    private val pendingPings = mutableMapOf<ULong, CompletableDeferred<Unit>>()
    private val pendingPingsMutex = Mutex()

    /**
     * Process an incoming frame from the wire.
     *
     * - OPEN: creates a new stream, emits it on [incomingStreams], and
     *   increments [activeStreamCount]
     * - DATA: routes payload to the matching stream
     * - CLOSE: tears down the matching stream and decrements [activeStreamCount]
     * - ERROR: propagates error and decrements [activeStreamCount]
     * - PING: immediately echoes a PONG with the same nonce via [onOutgoingFrame]
     * - PONG: completes a pending [sendPingAwaitPong] waiter, if any
     *
     * Unknown stream IDs for DATA/CLOSE/ERROR are silently ignored.
     * OPEN frames are rejected with an ERROR frame if the stream limit is exceeded.
     */
    suspend fun handleFrame(frame: MuxFrame) {
        when (frame) {
            is MuxFrame.Open -> handleOpen(frame)
            is MuxFrame.Data -> {
                val stream = streamsMutex.withLock { streams[frame.streamId] }
                stream?.receiveData(frame.payload)
            }
            is MuxFrame.Close -> {
                val removed = streamsMutex.withLock { streams.remove(frame.streamId) }
                if (removed != null) {
                    removed.receiveClose()
                    decrementActive()
                }
            }
            is MuxFrame.Error -> {
                val removed = streamsMutex.withLock { streams.remove(frame.streamId) }
                if (removed != null) {
                    removed.receiveError(frame.errorCode, frame.message)
                    decrementActive()
                }
            }
            is MuxFrame.Ping -> handlePing(frame)
            is MuxFrame.Pong -> handlePong(frame)
        }
    }

    private suspend fun handleOpen(frame: MuxFrame.Open) {
        val stream = streamsMutex.withLock {
            if (streams.size >= maxStreams) {
                null
            } else {
                val s = MuxStreamImpl(
                    id = frame.streamId,
                    onLocalClose = { decrementIfPresent(frame.streamId) }
                ) { outFrame -> onOutgoingFrame?.invoke(outFrame) }
                streams[frame.streamId] = s
                s
            }
        }
        if (stream != null) {
            // Increment BEFORE emitting so callers observing activeStreamCount
            // alongside incomingStreams never see a stream with count == 0.
            _activeStreamCount.value = _activeStreamCount.value + 1
            incomingChannel.send(stream)
        } else {
            log(
                LogLevel.WARN,
                "MuxDemux: rejecting stream ${frame.streamId}, " +
                    "limit $maxStreams reached"
            )
            onOutgoingFrame?.invoke(
                MuxFrame.Error(
                    frame.streamId,
                    MuxErrorCode.STREAM_REFUSED,
                    "Too many open streams"
                )
            )
        }
    }

    private suspend fun handlePing(frame: MuxFrame.Ping) {
        // Echo the nonce back immediately. We do not touch the stream map:
        // Ping/Pong ride on reserved stream_id=0 and MUST NOT allocate a stream.
        onOutgoingFrame?.invoke(MuxFrame.Pong(frame.nonce))
    }

    private suspend fun handlePong(frame: MuxFrame.Pong) {
        val waiter = pendingPingsMutex.withLock { pendingPings.remove(frame.nonce) }
        waiter?.complete(Unit)
    }

    /**
     * Send a Ping and suspend until a matching Pong arrives or [timeoutMillis]
     * elapses. Returns true if a Pong arrived in time.
     *
     * The optional [nonce] exists for tests; production code should leave it
     * unset so a fresh random nonce is generated.
     */
    suspend fun sendPingAwaitPong(timeoutMillis: Long, nonce: ULong = randomNonce()): Boolean {
        val send = onOutgoingFrame ?: return false
        val waiter = CompletableDeferred<Unit>()
        pendingPingsMutex.withLock { pendingPings[nonce] = waiter }
        return try {
            send(MuxFrame.Ping(nonce))
            withTimeoutOrNull(timeoutMillis) { waiter.await() } != null
        } catch (_: Exception) {
            false
        } finally {
            pendingPingsMutex.withLock { pendingPings.remove(nonce) }
        }
    }

    private suspend fun decrementIfPresent(streamId: UInt) {
        // Called when local close flows through MuxStreamImpl.close(). The
        // stream map entry is removed here (not in MuxStreamImpl) so that the
        // counter update stays under the demux mutex.
        val wasPresent = streamsMutex.withLock { streams.remove(streamId) != null }
        if (wasPresent) decrementActive()
    }

    private fun decrementActive() {
        _activeStreamCount.value = (_activeStreamCount.value - 1).coerceAtLeast(0)
    }

    /**
     * Gracefully close all streams (sending CLOSE frames) and clean up resources.
     */
    suspend fun closeAll() {
        val activeStreams = streamsMutex.withLock {
            val list = streams.values.toList()
            streams.clear()
            list
        }
        for (stream in activeStreams) {
            stream.close()
        }
        _activeStreamCount.value = 0
        incomingChannel.close()
        failAllPendingPings()
    }

    /**
     * Tear down all streams without sending CLOSE frames.
     * Used during error cleanup when the transport is already broken.
     */
    fun closeAllQuietly() {
        val activeStreams = synchronized(streams) {
            val list = streams.values.toList()
            streams.clear()
            list
        }
        for (stream in activeStreams) {
            stream.closeQuietly()
        }
        _activeStreamCount.value = 0
        incomingChannel.close()
        // Cancel pending ping waiters so any in-flight healthCheck() reports the
        // tunnel as dead rather than alive. Cancellation-only is deliberate: if
        // we also called complete(Unit), the completion would win and the waiter
        // would observe a successful pong (see issue #230). pendingPings is
        // accessed under its own Mutex on the send path, but closeAllQuietly is
        // non-suspend; synchronized(pendingPings) is adequate for tearing the
        // map down, and CompletableDeferred.cancel() is itself thread-safe.
        synchronized(pendingPings) {
            pendingPings.values.forEach { it.cancel() }
            pendingPings.clear()
        }
    }

    private suspend fun failAllPendingPings() {
        pendingPingsMutex.withLock {
            pendingPings.values.forEach { it.cancel() }
            pendingPings.clear()
        }
    }

    /**
     * Callback invoked when a stream wants to send a frame.
     * This is wired to the transport layer by the caller.
     */
    var onOutgoingFrame: (suspend (MuxFrame) -> Unit)? = null

    companion object {
        /** Default maximum number of concurrent streams per connection. */
        const val MAX_CONCURRENT_STREAMS = 100

        private fun randomNonce(): ULong {
            // kotlin.random.Random.nextLong gives Long; reinterpret as ULong.
            return kotlin.random.Random.nextLong().toULong()
        }
    }
}
