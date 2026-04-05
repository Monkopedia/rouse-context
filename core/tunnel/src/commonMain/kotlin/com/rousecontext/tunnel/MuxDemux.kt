package com.rousecontext.tunnel

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
            }
        }
    }

    /**
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
}
