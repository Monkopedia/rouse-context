package com.rousecontext.app.cert

import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch

/**
 * Minimal RFC 6455 WebSocket server used only by [MtlsWebSocketFactoryTest] to
 * drive OkHttp's `onOpen` / `onMessage` / `onClosing` callbacks.
 *
 * Not a production-grade implementation: it accepts exactly one client, completes
 * the HTTP upgrade handshake, optionally sends a pre-canned text frame, and
 * closes cleanly. Frame encoding supports only <126-byte unmasked text payloads
 * because that's all the unit test sends. Keeps the test self-contained — the
 * alternative (MockWebServer) isn't on the classpath, and pulling it in for one
 * test is overkill.
 */
class TestWebSocketServer : Closeable {

    private val serverSocket = ServerSocket(0)
    private val acceptThread: Thread
    val readyLatch = CountDownLatch(1)
    private val sendTextPayload: String?
    private val closeCodeToSend: Int?

    @Volatile
    var clientSocket: Socket? = null

    private val sendBinaryPayload: ByteArray?

    constructor(
        sendTextPayload: String? = null,
        sendBinaryPayload: ByteArray? = null,
        closeCodeToSend: Int? = null
    ) {
        this.sendTextPayload = sendTextPayload
        this.sendBinaryPayload = sendBinaryPayload
        this.closeCodeToSend = closeCodeToSend

        acceptThread = Thread {
            try {
                val s = serverSocket.accept()
                clientSocket = s
                handleConnection(s)
            } catch (_: Exception) {
                // server closed during test — ignore
            }
        }
        acceptThread.isDaemon = true
        acceptThread.start()
    }

    val port: Int get() = serverSocket.localPort

    private fun handleConnection(socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        // Parse HTTP upgrade request; grab Sec-WebSocket-Key.
        val headerBuffer = StringBuilder()
        val buf = ByteArray(1)
        while (!headerBuffer.endsWith("\r\n\r\n")) {
            val r = input.read(buf)
            if (r < 0) return
            headerBuffer.append(buf[0].toInt().toChar())
            if (headerBuffer.length > HEADER_MAX_BYTES) return
        }
        val keyLine = headerBuffer.lineSequence().firstOrNull {
            it.startsWith("Sec-WebSocket-Key:", ignoreCase = true)
        } ?: return
        val clientKey = keyLine.substringAfter(":").trim()
        val acceptKey = websocketAcceptKey(clientKey)

        val response = buildString {
            append("HTTP/1.1 101 Switching Protocols\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Accept: $acceptKey\r\n")
            append("\r\n")
        }
        output.write(response.toByteArray(Charsets.UTF_8))
        output.flush()
        readyLatch.countDown()

        // Send a text frame so OkHttp's onMessage(text) fires at the client.
        if (sendTextPayload != null) {
            val bytes = sendTextPayload.toByteArray(Charsets.UTF_8)
            require(bytes.size < TEXT_FRAME_SHORT_CAP)
            // 0x81 = FIN + text opcode, length byte with no mask bit.
            output.write(TEXT_FIN_OPCODE)
            output.write(bytes.size)
            output.write(bytes)
            output.flush()
        }

        if (sendBinaryPayload != null) {
            // 0x82 = FIN + binary opcode.
            require(sendBinaryPayload.size < TEXT_FRAME_SHORT_CAP)
            output.write(BINARY_FIN_OPCODE)
            output.write(sendBinaryPayload.size)
            output.write(sendBinaryPayload)
            output.flush()
        }

        if (closeCodeToSend != null) {
            // 0x88 = FIN + close opcode. Payload is 2-byte big-endian close code.
            val hi = (closeCodeToSend ushr BYTE_SHIFT) and BYTE_MASK
            val lo = closeCodeToSend and BYTE_MASK
            output.write(CLOSE_FIN_OPCODE)
            output.write(CLOSE_PAYLOAD_LEN)
            output.write(hi)
            output.write(lo)
            output.flush()
        }

        // Block reading so OkHttp can send its own frames and we cleanly tear
        // down when the test closes us out.
        try {
            while (true) {
                val n = input.read(ByteArray(READ_CHUNK_BYTES))
                if (n < 0) break
            }
        } catch (_: Exception) {
            // closed
        }
    }

    override fun close() {
        try {
            clientSocket?.close()
        } catch (_: Exception) {}
        try {
            serverSocket.close()
        } catch (_: Exception) {}
        acceptThread.interrupt()
    }

    private companion object {
        const val HEADER_MAX_BYTES = 4096
        const val TEXT_FIN_OPCODE = 0x81
        const val BINARY_FIN_OPCODE = 0x82
        const val CLOSE_FIN_OPCODE = 0x88
        const val TEXT_FRAME_SHORT_CAP = 126
        const val CLOSE_PAYLOAD_LEN = 2
        const val BYTE_SHIFT = 8
        const val BYTE_MASK = 0xff
        const val READ_CHUNK_BYTES = 1024
        const val WEBSOCKET_MAGIC_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        fun websocketAcceptKey(clientKey: String): String {
            val concat = clientKey + WEBSOCKET_MAGIC_GUID
            val sha1 = MessageDigest.getInstance("SHA-1")
                .digest(concat.toByteArray(Charsets.UTF_8))
            return Base64.getEncoder().encodeToString(sha1)
        }
    }
}
