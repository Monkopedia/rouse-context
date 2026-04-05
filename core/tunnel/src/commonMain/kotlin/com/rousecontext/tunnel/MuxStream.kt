package com.rousecontext.tunnel

<<<<<<< HEAD
import kotlinx.coroutines.flow.Flow

/**
 * A multiplexed stream within a tunnel connection.
 *
 * Each stream carries bidirectional byte data for a single MCP session.
 * Streams are identified by a unique [id] within the connection.
 */
interface MuxStream {
    /** Unique stream identifier within the mux connection. */
    val id: UInt

    /** Incoming data chunks from the remote peer. */
    val incoming: Flow<ByteArray>

    /** Send data to the remote peer. Produces a DATA frame on the wire. */
    suspend fun send(data: ByteArray)

    /** Close this stream gracefully. Sends a CLOSE frame. */
    suspend fun close()

    /** Whether this stream has been closed (locally or remotely). */
    val isClosed: Boolean
=======
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException

/**
 * A single multiplexed stream within a tunnel connection.
 * Provides read/write of raw bytes for one session.
 */
interface MuxStream {
    val streamId: Int
    val isClosed: Boolean

    suspend fun read(): ByteArray

    suspend fun write(data: ByteArray)

    suspend fun close()
}

/**
 * Default implementation of [MuxStream] backed by a [Channel].
 */
class MuxStreamImpl(
    override val streamId: Int,
    private val sendFrame: suspend (MuxFrame) -> Unit,
) : MuxStream {
    private val incoming = Channel<ByteArray>(Channel.BUFFERED)

    override var isClosed: Boolean = false
        private set

    override suspend fun read(): ByteArray =
        try {
            incoming.receive()
        } catch (_: ClosedReceiveChannelException) {
            throw TunnelError.ProtocolError("Stream $streamId is closed")
        }

    override suspend fun write(data: ByteArray) {
        check(!isClosed) { "Cannot write to closed stream $streamId" }
        sendFrame(MuxFrame(MuxFrame.Type.DATA, streamId, data))
    }

    override suspend fun close() {
        if (!isClosed) {
            isClosed = true
            sendFrame(MuxFrame(MuxFrame.Type.CLOSE, streamId))
            incoming.close()
        }
    }

    /**
     * Called by the demuxer to deliver incoming data to this stream.
     */
    internal suspend fun deliver(data: ByteArray) {
        incoming.send(data)
    }

    /**
     * Called by the demuxer when the remote side closes this stream.
     */
    internal fun remoteClose() {
        isClosed = true
        incoming.close()
    }
>>>>>>> feat/tunnel-websocket-tls
}
