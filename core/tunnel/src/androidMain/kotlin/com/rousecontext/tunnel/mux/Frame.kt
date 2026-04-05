package com.rousecontext.tunnel.mux

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Mux frame types matching the relay protocol.
 *
 * Wire format: type (u8) + stream_id (u32 BE) + payload (remaining bytes).
 * Header is 5 bytes. Payload length is implicit (determined by WebSocket message boundary).
 */
enum class FrameType(val value: Byte) {
    DATA(0x00),
    OPEN(0x01),
    CLOSE(0x02),
    ERROR(0x03),
    ;

    companion object {
        fun fromByte(b: Byte): FrameType =
            entries.find { it.value == b }
                ?: throw IllegalArgumentException("Unknown frame type: 0x${b.toUByte().toString(16)}")
    }
}

/**
 * Error codes for ERROR frames, matching the relay protocol.
 */
enum class ErrorCode(val value: Int) {
    STREAM_REFUSED(1),
    STREAM_RESET(2),
    PROTOCOL_ERROR(3),
    INTERNAL_ERROR(4),
    ;

    /**
     * Encode error payload: error_code (u32 BE) + optional UTF-8 message.
     */
    fun encodePayload(message: String? = null): ByteArray {
        val msgBytes = message?.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(4 + (msgBytes?.size ?: 0))
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.putInt(value)
        msgBytes?.let { buf.put(it) }
        return buf.array()
    }

    companion object {
        fun fromInt(v: Int): ErrorCode =
            entries.find { it.value == v }
                ?: throw IllegalArgumentException("Unknown error code: $v")

        /**
         * Decode error payload: error_code (u32 BE) + optional UTF-8 message.
         */
        fun decodePayload(data: ByteArray): Pair<ErrorCode, String?> {
            require(data.size >= 4) { "Error payload too short" }
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val code = fromInt(buf.int)
            val msg = if (data.size > 4) {
                String(data, 4, data.size - 4, Charsets.UTF_8)
            } else {
                null
            }
            return code to msg
        }
    }
}

/**
 * A mux protocol frame.
 *
 * Matches the Rust relay's frame format exactly:
 * - type: 1 byte (u8)
 * - stream_id: 4 bytes (u32 big-endian)
 * - payload: remaining bytes
 */
data class Frame(
    val frameType: FrameType,
    val streamId: Int,
    val payload: ByteArray = ByteArray(0),
) {
    /**
     * Encode the frame into a byte array for transmission over WebSocket.
     */
    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_SIZE + payload.size)
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.put(frameType.value)
        buf.putInt(streamId)
        buf.put(payload)
        return buf.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Frame) return false
        return frameType == other.frameType &&
            streamId == other.streamId &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = frameType.hashCode()
        result = 31 * result + streamId
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String =
        "Frame(type=$frameType, streamId=$streamId, payloadSize=${payload.size})"

    companion object {
        const val HEADER_SIZE = 5

        /**
         * Decode a frame from a byte array received over WebSocket.
         */
        fun decode(data: ByteArray): Frame {
            require(data.size >= HEADER_SIZE) {
                "Frame too short: need at least $HEADER_SIZE bytes, got ${data.size}"
            }
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val frameType = FrameType.fromByte(buf.get())
            val streamId = buf.int
            val payload = ByteArray(data.size - HEADER_SIZE)
            buf.get(payload)
            return Frame(frameType, streamId, payload)
        }
    }
}
