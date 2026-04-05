package com.rousecontext.tunnel.integration

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for the tunnel mux protocol.
 *
 * These tests verify that a Kotlin mux client can correctly communicate with
 * a relay server using the mux framing protocol over WebSocket. Since the real
 * Rust relay does not yet serve (main.rs has a TODO to bind TCP), these tests
 * use a mock relay server that implements the same mux framing protocol.
 *
 * Test scenario IDs from overall.md: 100-104.
 */
class TunnelRelayIntegrationTest {

    private lateinit var relay: MockRelayServer
    private lateinit var client: HttpClient

    @Before
    fun setUp() {
        relay = MockRelayServer()
        relay.start()
        client = HttpClient(io.ktor.client.engine.cio.CIO) {
            install(ClientWebSockets)
        }
    }

    @After
    fun tearDown() {
        client.close()
        relay.stop()
    }

    /**
     * Test 100: Kotlin tunnel connects to relay via WebSocket -- connection established.
     */
    @Test
    fun `connection established via WebSocket`() = runBlocking {
        withTimeout(5_000) {
            client.webSocket(
                host = "localhost",
                port = relay.port,
                path = "/ws",
            ) {
                // Connection established -- verify relay saw the connection
                relay.clientConnected.await()
            }
        }
    }

    /**
     * Test 101: OPEN/DATA/CLOSE frame round-trip through relay -- data integrity preserved.
     */
    @Test
    fun `open data close frame round trip preserves data integrity`() = runBlocking {
        withTimeout(5_000) {
            client.webSocket(
                host = "localhost",
                port = relay.port,
                path = "/ws",
            ) {
                relay.clientConnected.await()

                // Relay sends OPEN frame to device
                val openFrame = MuxFrame(FrameType.OPEN, streamId = 1)
                relay.outboundFrames.send(openFrame)

                // Device receives OPEN
                val receivedOpen = receiveNextMuxFrame()
                assert(receivedOpen.frameType == FrameType.OPEN) {
                    "Expected OPEN, got ${receivedOpen.frameType}"
                }
                assert(receivedOpen.streamId == 1) {
                    "Expected streamId=1, got ${receivedOpen.streamId}"
                }

                // Relay sends DATA frame with payload
                val testPayload = "Hello from relay".toByteArray()
                val dataFrame = MuxFrame(FrameType.DATA, streamId = 1, payload = testPayload)
                relay.outboundFrames.send(dataFrame)

                // Device receives DATA
                val receivedData = receiveNextMuxFrame()
                assert(receivedData.frameType == FrameType.DATA) {
                    "Expected DATA, got ${receivedData.frameType}"
                }
                assert(receivedData.payload.contentEquals(testPayload)) {
                    "Payload mismatch"
                }

                // Device sends DATA back (echo)
                val responsePayload = "Hello from device".toByteArray()
                val responseFrame = MuxFrame(FrameType.DATA, streamId = 1, payload = responsePayload)
                send(Frame.Binary(true, responseFrame.encode()))

                // Verify relay received it
                val relayReceived = relay.inboundChannel.receive()
                assert(relayReceived.frameType == FrameType.DATA) {
                    "Expected DATA, got ${relayReceived.frameType}"
                }
                assert(relayReceived.payload.contentEquals(responsePayload)) {
                    "Response payload mismatch"
                }

                // Relay sends CLOSE
                val closeFrame = MuxFrame(FrameType.CLOSE, streamId = 1)
                relay.outboundFrames.send(closeFrame)

                // Device receives CLOSE
                val receivedClose = receiveNextMuxFrame()
                assert(receivedClose.frameType == FrameType.CLOSE) {
                    "Expected CLOSE, got ${receivedClose.frameType}"
                }
                assert(receivedClose.streamId == 1) {
                    "Expected streamId=1, got ${receivedClose.streamId}"
                }
            }
        }
    }

    /**
     * Test 102: Multiple concurrent streams through relay -- independent and correct.
     */
    @Test
    fun `multiple concurrent streams are independent`() = runBlocking {
        withTimeout(5_000) {
            client.webSocket(
                host = "localhost",
                port = relay.port,
                path = "/ws",
            ) {
                relay.clientConnected.await()

                // Open two streams
                relay.outboundFrames.send(MuxFrame(FrameType.OPEN, streamId = 1))
                relay.outboundFrames.send(MuxFrame(FrameType.OPEN, streamId = 2))

                val open1 = receiveNextMuxFrame()
                val open2 = receiveNextMuxFrame()
                assert(open1.frameType == FrameType.OPEN)
                assert(open2.frameType == FrameType.OPEN)

                // Send DATA on stream 1
                val payload1 = "stream-1-data".toByteArray()
                relay.outboundFrames.send(MuxFrame(FrameType.DATA, streamId = 1, payload = payload1))

                // Send DATA on stream 2
                val payload2 = "stream-2-data".toByteArray()
                relay.outboundFrames.send(MuxFrame(FrameType.DATA, streamId = 2, payload = payload2))

                // Receive both DATA frames
                val data1 = receiveNextMuxFrame()
                val data2 = receiveNextMuxFrame()

                // Verify stream IDs and payloads are correct
                val received = mapOf(data1.streamId to data1.payload, data2.streamId to data2.payload)
                assert(received[1]?.contentEquals(payload1) == true) {
                    "Stream 1 payload mismatch"
                }
                assert(received[2]?.contentEquals(payload2) == true) {
                    "Stream 2 payload mismatch"
                }

                // Close stream 1 only
                relay.outboundFrames.send(MuxFrame(FrameType.CLOSE, streamId = 1))
                val close1 = receiveNextMuxFrame()
                assert(close1.frameType == FrameType.CLOSE)
                assert(close1.streamId == 1)

                // Stream 2 still works
                val morePayload = "stream-2-still-alive".toByteArray()
                relay.outboundFrames.send(
                    MuxFrame(FrameType.DATA, streamId = 2, payload = morePayload),
                )
                val moreData = receiveNextMuxFrame()
                assert(moreData.streamId == 2)
                assert(moreData.payload.contentEquals(morePayload))

                // Close stream 2
                relay.outboundFrames.send(MuxFrame(FrameType.CLOSE, streamId = 2))
                val close2 = receiveNextMuxFrame()
                assert(close2.frameType == FrameType.CLOSE)
                assert(close2.streamId == 2)
            }
        }
    }

    /**
     * Test 103: ERROR frame with error code is correctly parsed.
     *
     * Note: Testing with a real expired/invalid device cert against the real relay
     * is blocked because the relay does not yet serve. This test verifies ERROR
     * frame handling through the mock relay instead.
     */
    @Test
    fun `error frame with stream refused is correctly received`() = runBlocking {
        withTimeout(5_000) {
            client.webSocket(
                host = "localhost",
                port = relay.port,
                path = "/ws",
            ) {
                relay.clientConnected.await()

                // Relay sends ERROR frame (stream refused)
                val errorPayload = ErrorCode.STREAM_REFUSED.encodePayload("max streams exceeded")
                val errorFrame = MuxFrame(FrameType.ERROR, streamId = 99, payload = errorPayload)
                relay.outboundFrames.send(errorFrame)

                // Device receives ERROR
                val received = receiveNextMuxFrame()
                assert(received.frameType == FrameType.ERROR) {
                    "Expected ERROR, got ${received.frameType}"
                }
                assert(received.streamId == 99) {
                    "Expected streamId=99, got ${received.streamId}"
                }

                // Parse the error payload
                val (code, message) = ErrorCode.decodePayload(received.payload)
                assert(code == ErrorCode.STREAM_REFUSED) {
                    "Expected STREAM_REFUSED, got $code"
                }
                assert(message == "max streams exceeded") {
                    "Expected 'max streams exceeded', got '$message'"
                }
            }
        }
    }

    /**
     * Test 104: Graceful shutdown -- relay sends CLOSE for all streams.
     */
    @Test
    fun `graceful disconnect sends close for all streams`() = runBlocking {
        withTimeout(5_000) {
            val allCloseReceived = CompletableDeferred<List<MuxFrame>>()

            client.webSocket(
                host = "localhost",
                port = relay.port,
                path = "/ws",
            ) {
                relay.clientConnected.await()

                // Open three streams
                relay.outboundFrames.send(MuxFrame(FrameType.OPEN, streamId = 1))
                relay.outboundFrames.send(MuxFrame(FrameType.OPEN, streamId = 2))
                relay.outboundFrames.send(MuxFrame(FrameType.OPEN, streamId = 3))
                receiveNextMuxFrame() // OPEN 1
                receiveNextMuxFrame() // OPEN 2
                receiveNextMuxFrame() // OPEN 3

                // Relay sends CLOSE for all streams (graceful shutdown)
                relay.outboundFrames.send(MuxFrame(FrameType.CLOSE, streamId = 1))
                relay.outboundFrames.send(MuxFrame(FrameType.CLOSE, streamId = 2))
                relay.outboundFrames.send(MuxFrame(FrameType.CLOSE, streamId = 3))

                // Collect all CLOSE frames
                val closes = mutableListOf<MuxFrame>()
                repeat(3) {
                    val frame = receiveNextMuxFrame()
                    assert(frame.frameType == FrameType.CLOSE) {
                        "Expected CLOSE, got ${frame.frameType}"
                    }
                    closes.add(frame)
                }

                // Verify all stream IDs are closed
                val closedIds = closes.map { it.streamId }.toSet()
                assert(closedIds == setOf(1, 2, 3)) {
                    "Expected streams {1,2,3} closed, got $closedIds"
                }
            }
        }
    }

    /**
     * Test: Device can send CLOSE frame to relay.
     */
    @Test
    fun `device initiated close is received by relay`() = runBlocking {
        withTimeout(5_000) {
            client.webSocket(
                host = "localhost",
                port = relay.port,
                path = "/ws",
            ) {
                relay.clientConnected.await()

                // Device sends CLOSE for stream 42
                val closeFrame = MuxFrame(FrameType.CLOSE, streamId = 42)
                send(Frame.Binary(true, closeFrame.encode()))

                // Relay receives the CLOSE
                val received = relay.inboundChannel.receive()
                assert(received.frameType == FrameType.CLOSE) {
                    "Expected CLOSE, got ${received.frameType}"
                }
                assert(received.streamId == 42) {
                    "Expected streamId=42, got ${received.streamId}"
                }
            }
        }
    }

    /**
     * Test: Large payload round-trip preserves data integrity.
     */
    @Test
    fun `large payload round trip preserves integrity`() = runBlocking {
        withTimeout(5_000) {
            client.webSocket(
                host = "localhost",
                port = relay.port,
                path = "/ws",
            ) {
                relay.clientConnected.await()

                // 64KB payload
                val largePayload = ByteArray(65536) { (it % 256).toByte() }
                relay.outboundFrames.send(
                    MuxFrame(FrameType.DATA, streamId = 1, payload = largePayload),
                )

                val received = receiveNextMuxFrame()
                assert(received.payload.contentEquals(largePayload)) {
                    "Large payload mismatch: expected ${largePayload.size} bytes, " +
                        "got ${received.payload.size} bytes"
                }
            }
        }
    }

    /**
     * Receive the next binary WebSocket frame and decode it as a MuxFrame.
     */
    private suspend fun io.ktor.websocket.DefaultWebSocketSession.receiveNextMuxFrame(): MuxFrame {
        while (true) {
            val wsFrame = incoming.receive()
            if (wsFrame is Frame.Binary) {
                return MuxFrame.decode(wsFrame.readBytes())
            }
            // Skip non-binary frames (ping/pong/text)
        }
    }
}
