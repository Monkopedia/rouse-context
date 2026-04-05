package com.rousecontext.tunnel

/**
<<<<<<< HEAD
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
 *
 * Error codes:
 * - STREAM_REFUSED (1)
 * - STREAM_RESET (2)
 * - PROTOCOL_ERROR (3)
 * - INTERNAL_ERROR (4)
 */

/** Header size in bytes: 1 (type) + 4 (stream_id). */
const val MUX_HEADER_SIZE = 5

/** Frame type constants. */
object MuxFrameType {
    const val DATA: Byte = 0x00
    const val OPEN: Byte = 0x01
    const val CLOSE: Byte = 0x02
    const val ERROR: Byte = 0x03
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

    data class Error(
        override val streamId: UInt,
        val errorCode: UInt,
        val message: String,
    ) : MuxFrame()

    companion object
}
=======
 * A multiplexed frame carrying data for a specific stream over the shared WebSocket.
 *
 * Wire format (big-endian):
 * - 1 byte: type (OPEN=0x01, DATA=0x02, CLOSE=0x03)
 * - 4 bytes: streamId
 * - 4 bytes: payload length
 * - N bytes: payload
 */
data class MuxFrame(
    val type: Type,
    val streamId: Int,
    val payload: ByteArray = ByteArray(0),
) {
    enum class Type(val code: Byte) {
        OPEN(0x01),
        DATA(0x02),
        CLOSE(0x03),
        ;

        companion object {
            fun fromCode(code: Byte): Type =
                entries.firstOrNull { it.code == code }
                    ?: throw IllegalArgumentException("Unknown frame type: $code")
        }
    }

    /**
     * Serialize this frame to bytes in wire format.
     */
    fun encode(): ByteArray {
        val buffer = ByteArray(HEADER_SIZE + payload.size)
        buffer[0] = type.code
        buffer.putInt(1, streamId)
        buffer.putInt(5, payload.size)
        payload.copyInto(buffer, HEADER_SIZE)
        return buffer
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MuxFrame) return false
        return type == other.type &&
            streamId == other.streamId &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + streamId
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String = "MuxFrame(type=$type, streamId=$streamId, payloadSize=${payload.size})"

    companion object {
        const val HEADER_SIZE = 9

        /**
         * Decode a MuxFrame from raw bytes.
         */
        fun decode(data: ByteArray): MuxFrame {
            require(data.size >= HEADER_SIZE) { "Frame too short: ${data.size} bytes" }
            val type = Type.fromCode(data[0])
            val streamId = data.getInt(1)
            val payloadLength = data.getInt(5)
            require(data.size >= HEADER_SIZE + payloadLength) {
                "Frame truncated: expected ${HEADER_SIZE + payloadLength}, got ${data.size}"
            }
            val payload = data.copyOfRange(HEADER_SIZE, HEADER_SIZE + payloadLength)
            return MuxFrame(type, streamId, payload)
        }
    }
}

internal fun ByteArray.putInt(
    offset: Int,
    value: Int,
) {
    this[offset] = (value shr 24).toByte()
    this[offset + 1] = (value shr 16).toByte()
    this[offset + 2] = (value shr 8).toByte()
    this[offset + 3] = value.toByte()
}

internal fun ByteArray.getInt(offset: Int): Int =
    (this[offset].toInt() and 0xFF shl 24) or
        (this[offset + 1].toInt() and 0xFF shl 16) or
        (this[offset + 2].toInt() and 0xFF shl 8) or
        (this[offset + 3].toInt() and 0xFF)
>>>>>>> feat/tunnel-websocket-tls
