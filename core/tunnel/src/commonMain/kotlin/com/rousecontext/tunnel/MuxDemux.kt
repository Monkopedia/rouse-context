package com.rousecontext.tunnel

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
            }
        }
    }

    /**
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
}
