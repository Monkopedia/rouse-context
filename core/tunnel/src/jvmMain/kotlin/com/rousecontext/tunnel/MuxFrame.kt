package com.rousecontext.tunnel

/**
 * Mux binary frame protocol.
 *
 * Wire format: 5-byte header + variable payload.
 * - Byte 0: frame type (u8)
 * - Bytes 1-4: stream_id (u32 big-endian)
 * - Remaining: payload bytes
 *
 * Frame types:
 * - DATA (0x00): payload is raw bytes for the stream
 * - OPEN (0x01): payload is empty, signals new stream
 * - CLOSE (0x02): payload is empty, signals stream teardown
 * - ERROR (0x03): payload is error_code (u32 BE) + optional UTF-8 message
 * - PING (0x04): payload is nonce (u64 BE); stream_id MUST be 0
 * - PONG (0x05): payload is nonce (u64 BE); stream_id MUST be 0
 *
 * Ping / Pong provide an application-layer keepalive on top of the WebSocket
 * transport. The receiver of a PING MUST echo the nonce back in a PONG so the
 * originator can match request to response (and detect dead/half-open sockets
 * under Doze or NAT-rebind conditions that OkHttp's WebSocket-level pings miss).
 * See issue #179 for the motivating bug.
 *
 * Error codes:
 * - STREAM_REFUSED (1)
 * - STREAM_RESET (2)
 * - PROTOCOL_ERROR (3)
 * - INTERNAL_ERROR (4)
 */

/** Header size in bytes: 1 (type) + 4 (stream_id). */
const val MUX_HEADER_SIZE = 5

/** PING/PONG nonce payload length in bytes. */
const val MUX_PING_NONCE_SIZE = 8

/** Frame type constants. */
object MuxFrameType {
    const val DATA: Byte = 0x00
    const val OPEN: Byte = 0x01
    const val CLOSE: Byte = 0x02
    const val ERROR: Byte = 0x03
    const val PING: Byte = 0x04
    const val PONG: Byte = 0x05
}

/** Mux error codes sent in ERROR frame payloads. */
object MuxErrorCode {
    const val STREAM_REFUSED: UInt = 1u
    const val STREAM_RESET: UInt = 2u
    const val PROTOCOL_ERROR: UInt = 3u
    const val INTERNAL_ERROR: UInt = 4u
}

/** A decoded mux frame. */
sealed class MuxFrame {
    abstract val streamId: UInt

    data class Data(override val streamId: UInt, val payload: ByteArray) : MuxFrame() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Data) return false
            return streamId == other.streamId && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int = 31 * streamId.hashCode() + payload.contentHashCode()
    }

    data class Open(override val streamId: UInt) : MuxFrame()
    data class Close(override val streamId: UInt) : MuxFrame()

    data class Error(override val streamId: UInt, val errorCode: UInt, val message: String) :
        MuxFrame()

    /**
     * Ping keepalive. Stream ID is always 0 (the reserved control-channel ID).
     * The peer MUST reply with a [Pong] carrying the same [nonce] so the
     * originator can correlate request/response.
     */
    data class Ping(val nonce: ULong) : MuxFrame() {
        override val streamId: UInt = 0u
    }

    /** Reply to a [Ping]. [nonce] MUST equal the Ping's nonce. */
    data class Pong(val nonce: ULong) : MuxFrame() {
        override val streamId: UInt = 0u
    }

    companion object
}
