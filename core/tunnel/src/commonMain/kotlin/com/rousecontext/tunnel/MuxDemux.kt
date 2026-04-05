package com.rousecontext.tunnel

<<<<<<< HEAD
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Multiplexer/demultiplexer that manages streams over a single connection.
 *
 * Accepts raw frames from the wire, routes DATA to the correct stream,
 * handles OPEN/CLOSE/ERROR lifecycle, and provides a flow of new streams.
 */
class MuxDemux {

    private val streams = mutableMapOf<UInt, MuxStreamImpl>()
    private val incomingChannel = Channel<MuxStreamImpl>(Channel.BUFFERED)

    /**
     * Flow of newly opened streams (from remote OPEN frames).
     */
    val incomingStreams: Flow<MuxStreamImpl>
        get() = incomingChannel.receiveAsFlow()

    /**
     * Process an incoming frame from the wire.
     *
     * - OPEN: creates a new stream and emits it on [incomingStreams]
     * - DATA: routes payload to the matching stream
     * - CLOSE: tears down the matching stream
     * - ERROR: propagates error to the matching stream
     *
     * Unknown stream IDs for DATA/CLOSE/ERROR are silently ignored.
     */
    suspend fun handleFrame(frame: MuxFrame) {
        when (frame) {
            is MuxFrame.Open -> {
                val stream = MuxStreamImpl(frame.streamId) { outFrame ->
                    onOutgoingFrame?.invoke(outFrame)
                }
                streams[frame.streamId] = stream
                incomingChannel.send(stream)
            }
            is MuxFrame.Data -> {
                streams[frame.streamId]?.receiveData(frame.payload)
            }
            is MuxFrame.Close -> {
                streams.remove(frame.streamId)?.receiveClose()
            }
            is MuxFrame.Error -> {
                streams.remove(frame.streamId)?.receiveError(frame.errorCode, frame.message)
=======
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Multiplexer/demultiplexer that manages streams over a single transport.
 * Routes incoming frames to the appropriate [MuxStreamImpl] and provides
 * a flow of newly opened sessions.
 */
class MuxDemux(
    private val sendFrame: suspend (MuxFrame) -> Unit,
) {
    private val streams = mutableMapOf<Int, MuxStreamImpl>()
    private val mutex = Mutex()

    private val _incomingSessions = MutableSharedFlow<MuxStream>(extraBufferCapacity = 16)
    val incomingSessions: SharedFlow<MuxStream> = _incomingSessions.asSharedFlow()

    /**
     * Process an incoming frame from the transport.
     */
    suspend fun onFrame(frame: MuxFrame) {
        when (frame.type) {
            MuxFrame.Type.OPEN -> {
                val stream = MuxStreamImpl(frame.streamId, sendFrame)
                mutex.withLock { streams[frame.streamId] = stream }
                _incomingSessions.emit(stream)
            }
            MuxFrame.Type.DATA -> {
                val stream = mutex.withLock { streams[frame.streamId] }
                stream?.deliver(frame.payload)
            }
            MuxFrame.Type.CLOSE -> {
                val stream = mutex.withLock { streams.remove(frame.streamId) }
                stream?.remoteClose()
>>>>>>> feat/tunnel-websocket-tls
            }
        }
    }

    /**
<<<<<<< HEAD
     * Close all streams and clean up resources.
     */
    suspend fun closeAll() {
        streams.values.forEach { it.receiveClose() }
        streams.clear()
        incomingChannel.close()
    }

    /**
     * Callback invoked when a stream wants to send a frame.
     * This is wired to the transport layer by the caller.
     */
    var onOutgoingFrame: (suspend (MuxFrame) -> Unit)? = null
=======
     * Close all active streams and send CLOSE frames.
     */
    suspend fun closeAll() {
        val activeStreams = mutex.withLock {
            val copy = streams.values.toList()
            streams.clear()
            copy
        }
        for (stream in activeStreams) {
            stream.close()
        }
    }

    /**
     * Get an active stream by ID, or null if not found.
     */
    suspend fun getStream(streamId: Int): MuxStreamImpl? = mutex.withLock { streams[streamId] }
>>>>>>> feat/tunnel-websocket-tls
}
