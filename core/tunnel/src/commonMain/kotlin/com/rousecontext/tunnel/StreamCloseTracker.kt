package com.rousecontext.tunnel

import kotlinx.coroutines.flow.Flow

/**
 * Wraps a [MuxStream] to invoke a callback when the stream is closed.
 * Used by [TunnelClientImpl] to track active stream count for state transitions.
 */
internal class StreamCloseTracker(
    private val delegate: MuxStream,
    private val onClose: () -> Unit
) : MuxStream {
    override val id: UInt get() = delegate.id
    override val incoming: Flow<ByteArray> get() = delegate.incoming
    override val isClosed: Boolean get() = delegate.isClosed

    override suspend fun send(data: ByteArray) = delegate.send(data)

    override suspend fun read(): ByteArray = delegate.read()

    override suspend fun write(data: ByteArray) = delegate.send(data)

    override suspend fun close() {
        try {
            delegate.close()
        } finally {
            onClose()
        }
    }
}
