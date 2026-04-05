package com.rousecontext.tunnel

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Concrete implementation of [MuxStream] backed by the [MuxDemux].
 */
class MuxStreamImpl(
    override val id: UInt,
    private val sendFrame: suspend (MuxFrame) -> Unit
) : MuxStream {

    private val dataChannel = Channel<ByteArray>(Channel.BUFFERED)

    @Volatile
    private var closed = false

    override val incoming: Flow<ByteArray>
        get() = dataChannel.receiveAsFlow()

    override suspend fun send(data: ByteArray) {
        check(!closed) { "Cannot send on closed stream $id" }
        sendFrame(MuxFrame.Data(id, data))
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        sendFrame(MuxFrame.Close(id))
        dataChannel.close()
    }

    override val isClosed: Boolean
        get() = closed

    override suspend fun read(): ByteArray = incoming.first()

    /**
     * Called by [MuxDemux] when a DATA frame arrives for this stream.
     */
    internal suspend fun receiveData(data: ByteArray) {
        if (!closed) {
            dataChannel.send(data)
        }
    }

    /**
     * Called by [MuxDemux] when a CLOSE frame arrives for this stream.
     */
    internal fun receiveClose() {
        closed = true
        dataChannel.close()
    }

    /**
     * Called by [MuxDemux] when an ERROR frame arrives for this stream.
     */
    internal fun receiveError(errorCode: UInt, message: String) {
        closed = true
        dataChannel.close()
    }
}
