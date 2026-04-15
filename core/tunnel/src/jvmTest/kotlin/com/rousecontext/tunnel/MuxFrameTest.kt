package com.rousecontext.tunnel

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MuxFrameTest {

    @Test
    fun encodeDataFrame() {
        val frame = MuxFrame.Data(streamId = 42u, payload = byteArrayOf(0x01, 0x02, 0x03))
        val encoded = MuxCodec.encode(frame)

        assertEquals(MUX_HEADER_SIZE + 3, encoded.size)
        assertEquals(MuxFrameType.DATA, encoded[0])
        // stream_id = 42 in big-endian u32
        assertEquals(0x00.toByte(), encoded[1])
        assertEquals(0x00.toByte(), encoded[2])
        assertEquals(0x00.toByte(), encoded[3])
        assertEquals(42.toByte(), encoded[4])
        // payload
        assertContentEquals(byteArrayOf(0x01, 0x02, 0x03), encoded.sliceArray(5..7))
    }

    @Test
    fun encodeOpenFrame() {
        val frame = MuxFrame.Open(streamId = 1u)
        val encoded = MuxCodec.encode(frame)

        assertEquals(MUX_HEADER_SIZE, encoded.size)
        assertEquals(MuxFrameType.OPEN, encoded[0])
        assertEquals(0x00.toByte(), encoded[1])
        assertEquals(0x00.toByte(), encoded[2])
        assertEquals(0x00.toByte(), encoded[3])
        assertEquals(0x01.toByte(), encoded[4])
    }

    @Test
    fun encodeCloseFrame() {
        val frame = MuxFrame.Close(streamId = 256u)
        val encoded = MuxCodec.encode(frame)

        assertEquals(MUX_HEADER_SIZE, encoded.size)
        assertEquals(MuxFrameType.CLOSE, encoded[0])
        // 256 = 0x00000100
        assertEquals(0x00.toByte(), encoded[1])
        assertEquals(0x00.toByte(), encoded[2])
        assertEquals(0x01.toByte(), encoded[3])
        assertEquals(0x00.toByte(), encoded[4])
    }

    @Test
    fun encodeErrorFrameWithMessage() {
        val frame = MuxFrame.Error(
            streamId = 7u,
            errorCode = MuxErrorCode.PROTOCOL_ERROR,
            message = "bad"
        )
        val encoded = MuxCodec.encode(frame)

        assertEquals(MuxFrameType.ERROR, encoded[0])
        // stream_id = 7
        assertEquals(7.toByte(), encoded[4])
        // error_code = 3 (PROTOCOL_ERROR) as u32 BE at offset 5
        assertEquals(0x00.toByte(), encoded[5])
        assertEquals(0x00.toByte(), encoded[6])
        assertEquals(0x00.toByte(), encoded[7])
        assertEquals(0x03.toByte(), encoded[8])
        // message "bad" as UTF-8 at offset 9
        val msgBytes = encoded.sliceArray(9 until encoded.size)
        assertEquals("bad", msgBytes.decodeToString())
    }

    @Test
    fun encodeErrorFrameWithEmptyMessage() {
        val frame = MuxFrame.Error(
            streamId = 1u,
            errorCode = MuxErrorCode.STREAM_REFUSED,
            message = ""
        )
        val encoded = MuxCodec.encode(frame)

        // header (5) + error_code (4) + no message
        assertEquals(MUX_HEADER_SIZE + 4, encoded.size)
    }

    @Test
    fun decodeDataFrame() {
        val bytes = byteArrayOf(
            MuxFrameType.DATA,
            0x00,
            0x00,
            0x00,
            // stream_id = 10
            0x0A,
            // payload
            0xAA.toByte(),
            0xBB.toByte()
        )

        val frame = MuxCodec.decode(bytes)

        val data = frame as MuxFrame.Data
        assertEquals(10u, data.streamId)
        assertContentEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), data.payload)
    }

    @Test
    fun decodeOpenFrame() {
        val bytes = byteArrayOf(
            MuxFrameType.OPEN,
            0x00,
            0x00,
            0x01,
            // stream_id = 256
            0x00
        )

        val frame = MuxCodec.decode(bytes)

        val open = frame as MuxFrame.Open
        assertEquals(256u, open.streamId)
    }

    @Test
    fun decodeCloseFrame() {
        val bytes = byteArrayOf(
            MuxFrameType.CLOSE,
            0x00,
            0x00,
            0x00,
            // stream_id = 1
            0x01
        )

        val frame = MuxCodec.decode(bytes)

        val close = frame as MuxFrame.Close
        assertEquals(1u, close.streamId)
    }

    @Test
    fun decodeErrorFrameWithMessage() {
        val msgBytes = "refused".encodeToByteArray()
        val bytes = byteArrayOf(
            MuxFrameType.ERROR,
            // stream_id = 5
            0x00, 0x00, 0x00, 0x05,
            // error_code = STREAM_REFUSED (1)
            0x00, 0x00, 0x00, 0x01
        ) + msgBytes

        val frame = MuxCodec.decode(bytes)

        val error = frame as MuxFrame.Error
        assertEquals(5u, error.streamId)
        assertEquals(MuxErrorCode.STREAM_REFUSED, error.errorCode)
        assertEquals("refused", error.message)
    }

    @Test
    fun decodeErrorFrameWithoutMessage() {
        val bytes = byteArrayOf(
            MuxFrameType.ERROR,
            // stream_id = 2
            0x00, 0x00, 0x00, 0x02,
            // error_code = INTERNAL_ERROR (4)
            0x00, 0x00, 0x00, 0x04
        )

        val frame = MuxCodec.decode(bytes)

        val error = frame as MuxFrame.Error
        assertEquals(2u, error.streamId)
        assertEquals(MuxErrorCode.INTERNAL_ERROR, error.errorCode)
        assertEquals("", error.message)
    }

    @Test
    fun decodeUnknownFrameTypeThrows() {
        val bytes = byteArrayOf(
            // unknown type
            0x7F,
            0x00,
            0x00,
            0x00,
            0x01
        )

        assertFailsWith<MuxProtocolException> {
            MuxCodec.decode(bytes)
        }
    }

    @Test
    fun decodeTruncatedFrameThrows() {
        val bytes = byteArrayOf(MuxFrameType.DATA, 0x00, 0x00) // only 3 bytes, need 5

        assertFailsWith<MuxProtocolException> {
            MuxCodec.decode(bytes)
        }
    }

    @Test
    fun decodeTruncatedErrorPayloadThrows() {
        // ERROR frame needs at least 4 bytes of payload for error_code
        val bytes = byteArrayOf(
            MuxFrameType.ERROR,
            0x00,
            0x00,
            0x00,
            0x01,
            0x00,
            // only 2 bytes of error code, need 4
            0x00
        )

        assertFailsWith<MuxProtocolException> {
            MuxCodec.decode(bytes)
        }
    }

    @Test
    fun roundTripDataFrame() {
        val original = MuxFrame.Data(streamId = 999u, payload = byteArrayOf(1, 2, 3, 4, 5))
        val decoded = MuxCodec.decode(MuxCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun roundTripOpenFrame() {
        val original = MuxFrame.Open(streamId = 0xFFFFFFFFu)
        val decoded = MuxCodec.decode(MuxCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun roundTripCloseFrame() {
        val original = MuxFrame.Close(streamId = 0u)
        val decoded = MuxCodec.decode(MuxCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun roundTripErrorFrame() {
        val original = MuxFrame.Error(
            streamId = 123u,
            errorCode = MuxErrorCode.STREAM_RESET,
            message = "connection lost"
        )
        val decoded = MuxCodec.decode(MuxCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun encodeDataFrameEmptyPayload() {
        val frame = MuxFrame.Data(streamId = 1u, payload = byteArrayOf())
        val encoded = MuxCodec.encode(frame)
        assertEquals(MUX_HEADER_SIZE, encoded.size)
    }

    @Test
    fun largeStreamId() {
        val frame = MuxFrame.Open(streamId = 0xFFFFFFFFu)
        val encoded = MuxCodec.encode(frame)

        assertEquals(0xFF.toByte(), encoded[1])
        assertEquals(0xFF.toByte(), encoded[2])
        assertEquals(0xFF.toByte(), encoded[3])
        assertEquals(0xFF.toByte(), encoded[4])

        val decoded = MuxCodec.decode(encoded) as MuxFrame.Open
        assertEquals(0xFFFFFFFFu, decoded.streamId)
    }
}
