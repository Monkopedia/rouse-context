package com.rousecontext.tunnel.mux

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Unit tests for mux Frame encoding/decoding.
 *
 * These verify wire-format compatibility with the Rust relay's frame implementation.
 */
class FrameTest {

    @Test
    fun `encode and decode DATA frame round-trips`() {
        val payload = "hello".toByteArray()
        val frame = Frame(FrameType.DATA, streamId = 42, payload = payload)
        val encoded = frame.encode()
        val decoded = Frame.decode(encoded)

        assertEquals(FrameType.DATA, decoded.frameType)
        assertEquals(42, decoded.streamId)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun `encode and decode OPEN frame round-trips`() {
        val frame = Frame(FrameType.OPEN, streamId = 1)
        val encoded = frame.encode()
        val decoded = Frame.decode(encoded)

        assertEquals(FrameType.OPEN, decoded.frameType)
        assertEquals(1, decoded.streamId)
        assertEquals(0, decoded.payload.size)
    }

    @Test
    fun `encode and decode CLOSE frame round-trips`() {
        val frame = Frame(FrameType.CLOSE, streamId = 7)
        val encoded = frame.encode()
        val decoded = Frame.decode(encoded)

        assertEquals(FrameType.CLOSE, decoded.frameType)
        assertEquals(7, decoded.streamId)
        assertEquals(0, decoded.payload.size)
    }

    @Test
    fun `encode and decode ERROR frame with payload round-trips`() {
        val errorPayload = ErrorCode.STREAM_REFUSED.encodePayload("limit exceeded")
        val frame = Frame(FrameType.ERROR, streamId = 5, payload = errorPayload)
        val encoded = frame.encode()
        val decoded = Frame.decode(encoded)

        assertEquals(FrameType.ERROR, decoded.frameType)
        assertEquals(5, decoded.streamId)
        val (code, message) = ErrorCode.decodePayload(decoded.payload)
        assertEquals(ErrorCode.STREAM_REFUSED, code)
        assertEquals("limit exceeded", message)
    }

    @Test
    fun `wire format matches Rust relay`() {
        // Manually construct expected bytes:
        // type=DATA(0x00), streamId=1 (big-endian: 0x00000001), payload="AB"
        val expected = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x01, 0x41, 0x42)
        val frame = Frame(FrameType.DATA, streamId = 1, payload = "AB".toByteArray())
        assertArrayEquals(expected, frame.encode())
    }

    @Test
    fun `header size is 5 bytes`() {
        assertEquals(5, Frame.HEADER_SIZE)
        val frame = Frame(FrameType.OPEN, streamId = 0)
        assertEquals(5, frame.encode().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode rejects frame shorter than header`() {
        Frame.decode(byteArrayOf(0x00, 0x01, 0x02))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode rejects unknown frame type`() {
        Frame.decode(byteArrayOf(0x0F, 0x00, 0x00, 0x00, 0x01))
    }

    @Test
    fun `large stream id encodes correctly`() {
        val frame = Frame(FrameType.DATA, streamId = 0x7FFFFFFF)
        val decoded = Frame.decode(frame.encode())
        assertEquals(0x7FFFFFFF, decoded.streamId)
    }

    @Test
    fun `error code encode and decode round-trips`() {
        for (code in ErrorCode.entries) {
            val payload = code.encodePayload("test message")
            val (decodedCode, decodedMsg) = ErrorCode.decodePayload(payload)
            assertEquals(code, decodedCode)
            assertEquals("test message", decodedMsg)
        }
    }

    @Test
    fun `error code without message`() {
        val payload = ErrorCode.INTERNAL_ERROR.encodePayload()
        val (code, message) = ErrorCode.decodePayload(payload)
        assertEquals(ErrorCode.INTERNAL_ERROR, code)
        assertEquals(null, message)
    }

    @Test
    fun `frame equality uses payload content`() {
        val a = Frame(FrameType.DATA, 1, "hello".toByteArray())
        val b = Frame(FrameType.DATA, 1, "hello".toByteArray())
        val c = Frame(FrameType.DATA, 1, "world".toByteArray())
        assertEquals(a, b)
        assertNotEquals(a, c)
    }
}
