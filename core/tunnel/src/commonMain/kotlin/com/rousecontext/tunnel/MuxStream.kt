package com.rousecontext.tunnel

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
}
