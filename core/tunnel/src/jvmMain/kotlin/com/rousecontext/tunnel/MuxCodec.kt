package com.rousecontext.tunnel

/**
 * Encoder/decoder for the mux binary framing protocol.
 */
object MuxCodec {
    /**
     * Encode a [MuxFrame] to its wire representation.
     *
     * @return byte array containing the 5-byte header + payload
     */
    fun encode(frame: MuxFrame): ByteArray {
        val type: Byte
        val payload: ByteArray

        when (frame) {
            is MuxFrame.Data -> {
                type = MuxFrameType.DATA
                payload = frame.payload
            }
            is MuxFrame.Open -> {
                type = MuxFrameType.OPEN
                payload = byteArrayOf()
            }
            is MuxFrame.Close -> {
                type = MuxFrameType.CLOSE
                payload = byteArrayOf()
            }
            is MuxFrame.Error -> {
                type = MuxFrameType.ERROR
                val msgBytes = frame.message.encodeToByteArray()
                payload = ByteArray(4 + msgBytes.size).also { buf ->
                    putUInt32BE(buf, 0, frame.errorCode)
                    msgBytes.copyInto(buf, destinationOffset = 4)
                }
            }
        }

        val result = ByteArray(MUX_HEADER_SIZE + payload.size)
        result[0] = type
        putUInt32BE(result, 1, frame.streamId)
        payload.copyInto(result, destinationOffset = MUX_HEADER_SIZE)
        return result
    }

    /**
     * Decode a single [MuxFrame] from a byte array.
     *
     * @param data byte array containing at least [MUX_HEADER_SIZE] bytes
     * @return the decoded frame
     * @throws MuxProtocolException on invalid data
     */
    fun decode(data: ByteArray): MuxFrame {
        if (data.size < MUX_HEADER_SIZE) {
            throw MuxProtocolException(
                "Frame too short: ${data.size} bytes, need at least $MUX_HEADER_SIZE"
            )
        }

        val type = data[0]
        val streamId = getUInt32BE(data, 1)
        val payload = data.copyOfRange(MUX_HEADER_SIZE, data.size)

        return when (type) {
            MuxFrameType.DATA -> MuxFrame.Data(streamId, payload)
            MuxFrameType.OPEN -> MuxFrame.Open(streamId)
            MuxFrameType.CLOSE -> MuxFrame.Close(streamId)
            MuxFrameType.ERROR -> {
                if (payload.size < 4) {
                    throw MuxProtocolException(
                        "ERROR frame payload too short: ${payload.size} bytes, need at least 4"
                    )
                }
                val errorCode = getUInt32BE(payload, 0)
                val message = if (payload.size > 4) {
                    payload.copyOfRange(4, payload.size).decodeToString()
                } else {
                    ""
                }
                MuxFrame.Error(streamId, errorCode, message)
            }
            else -> throw MuxProtocolException(
                "Unknown frame type: 0x${type.toUByte().toString(16)}"
            )
        }
    }

    private fun putUInt32BE(buf: ByteArray, offset: Int, value: UInt) {
        buf[offset] = (value shr 24).toByte()
        buf[offset + 1] = (value shr 16).toByte()
        buf[offset + 2] = (value shr 8).toByte()
        buf[offset + 3] = value.toByte()
    }

    private fun getUInt32BE(buf: ByteArray, offset: Int): UInt = (
        (buf[offset].toUByte().toUInt() shl 24) or
            (buf[offset + 1].toUByte().toUInt() shl 16) or
            (buf[offset + 2].toUByte().toUInt() shl 8) or
            buf[offset + 3].toUByte().toUInt()
        )
}

/** Exception thrown when mux protocol framing is violated. */
class MuxProtocolException(message: String) : Exception(message)
