package com.rousecontext.tunnel

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Multiplexer/demultiplexer that manages streams over a single connection.
 *
 * Accepts raw frames from the wire, routes DATA to the correct stream,
 * handles OPEN/CLOSE/ERROR lifecycle, and provides a flow of new streams.
 *
 * Thread-safe: all access to the stream map is synchronized via a [Mutex].
 */
class MuxDemux {

    private val streams = mutableMapOf<UInt, MuxStreamImpl>()
    private val streamsMutex = Mutex()
    private val incomingChannel = Channel<MuxStreamImpl>(Channel.BUFFERED)

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
     * Process an incoming frame from the wire.
     *
     * - OPEN: creates a new stream and emits it on [incomingStreams]
     * - DATA: routes payload to the matching stream
     * - CLOSE: tears down the matching stream
     * - ERROR: propagates error to the matching stream
     *
     * Unknown stream IDs for DATA/CLOSE/ERROR are silently ignored.
     * OPEN frames are rejected with an ERROR frame if the stream limit is exceeded.
     */
    suspend fun handleFrame(frame: MuxFrame) {
        when (frame) {
            is MuxFrame.Open -> {
                val stream = streamsMutex.withLock {
                    if (streams.size >= maxStreams) {
                        // Reject: too many open streams
                        null
                    } else {
                        val s = MuxStreamImpl(frame.streamId) { outFrame ->
                            onOutgoingFrame?.invoke(outFrame)
                        }
                        streams[frame.streamId] = s
                        s
                    }
                }
                if (stream != null) {
                    incomingChannel.send(stream)
                } else {
                    println(
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
            is MuxFrame.Data -> {
                val stream = streamsMutex.withLock { streams[frame.streamId] }
                stream?.receiveData(frame.payload)
            }
            is MuxFrame.Close -> {
                val stream = streamsMutex.withLock { streams.remove(frame.streamId) }
                stream?.receiveClose()
            }
            is MuxFrame.Error -> {
                val stream = streamsMutex.withLock { streams.remove(frame.streamId) }
                stream?.receiveError(frame.errorCode, frame.message)
            }
        }
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
        incomingChannel.close()
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
        incomingChannel.close()
    }

    /**
     * Callback invoked when a stream wants to send a frame.
     * This is wired to the transport layer by the caller.
     */
    var onOutgoingFrame: (suspend (MuxFrame) -> Unit)? = null

    companion object {
        /** Default maximum number of concurrent streams per connection. */
        const val MAX_CONCURRENT_STREAMS = 100
    }
}
