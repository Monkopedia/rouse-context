package com.rousecontext.bridge

import com.rousecontext.mcp.core.InMemoryProviderRegistry
import com.rousecontext.mcp.core.InMemoryTokenStore
import com.rousecontext.tunnel.MuxStream
import com.rousecontext.tunnel.TunnelClient
import com.rousecontext.tunnel.TunnelError
import com.rousecontext.tunnel.TunnelState
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tests for [TunnelSessionManager] verifying concurrent session handling.
 */
class TunnelSessionManagerTest {

    private val mcpJson = Json { ignoreUnknownKeys = true }

    @Test
    fun `concurrent streams handled independently`() = runBlocking {
        val certStore = TestCertHelper()
        val registry = InMemoryProviderRegistry()
        registry.register("test", EchoProvider())
        registry.setEnabled("test", true)
        val tokenStore = InMemoryTokenStore()
        val token = tokenStore.createToken("test", "manager-test-client")

        val fakeTunnel = FakeTunnelClient()
        val handler = SessionHandler(
            certProvider = certStore,
            mcpSessionFactory = KtorMcpSessionFactory(registry, tokenStore)
        )
        val manager = TunnelSessionManager(fakeTunnel, handler, this)
        manager.start()

        // Wait for the manager's collector to subscribe
        withTimeout(5_000) {
            fakeTunnel.subscriberCount.first { it > 0 }
        }

        // Create two independent mux stream pairs
        val (serverStream1, clientStream1) = createMuxPipe(1u)
        val (serverStream2, clientStream2) = createMuxPipe(2u)

        // Emit both streams as incoming sessions (in background since no buffer)
        launch { fakeTunnel.emitSession(serverStream1) }
        launch { fakeTunnel.emitSession(serverStream2) }

        // Client side: TLS handshake for both streams
        val clientIo1 = CompletableDeferred<Pair<InputStream, OutputStream>>()
        val clientIo2 = CompletableDeferred<Pair<InputStream, OutputStream>>()
        launch(Dispatchers.IO) {
            clientIo1.complete(tlsClientHandshake(certStore, clientStream1))
        }
        launch(Dispatchers.IO) {
            clientIo2.complete(tlsClientHandshake(certStore, clientStream2))
        }

        val (clientIn1, clientOut1) = withTimeout(10_000) { clientIo1.await() }
        val (clientIn2, clientOut2) = withTimeout(10_000) { clientIo2.await() }

        // Initialize both sessions
        val initRequest = mcpJsonRpc(
            "initialize",
            """{"protocolVersion":"2025-03-26","capabilities":{},""" +
                """"clientInfo":{"name":"manager-test","version":"1.0"}}"""
        )

        val init1 = withTimeout(10_000) {
            httpPost(clientIn1, clientOut1, "/test/mcp", initRequest, token)
        }
        val init2 = withTimeout(10_000) {
            httpPost(clientIn2, clientOut2, "/test/mcp", initRequest, token)
        }

        assertTrue(
            mcpJson.parseToJsonElement(init1).jsonObject["result"] != null,
            "Stream 1 initialize should succeed"
        )
        assertTrue(
            mcpJson.parseToJsonElement(init2).jsonObject["result"] != null,
            "Stream 2 initialize should succeed"
        )

        // Send different tool calls on each stream
        val call1Request = mcpJsonRpc(
            "tools/call",
            """{"name":"echo","arguments":{"message":"stream-one"}}""",
            id = 2
        )
        val call2Request = mcpJsonRpc(
            "tools/call",
            """{"name":"echo","arguments":{"message":"stream-two"}}""",
            id = 2
        )

        val call1Response = withTimeout(10_000) {
            httpPost(clientIn1, clientOut1, "/test/mcp", call1Request, token)
        }
        val call2Response = withTimeout(10_000) {
            httpPost(clientIn2, clientOut2, "/test/mcp", call2Request, token)
        }

        val text1 = mcpJson.parseToJsonElement(call1Response).jsonObject["result"]
            ?.jsonObject?.get("content")?.jsonArray
            ?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content
        val text2 = mcpJson.parseToJsonElement(call2Response).jsonObject["result"]
            ?.jsonObject?.get("content")?.jsonArray
            ?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content

        assertEquals("stream-one", text1, "Stream 1 should get its own response")
        assertEquals("stream-two", text2, "Stream 2 should get its own response")

        manager.stop()
        coroutineContext.cancelChildren()
    }

    // -- Fake TunnelClient --

    private class FakeTunnelClient : TunnelClient {
        private val _state = MutableStateFlow(TunnelState.CONNECTED)
        private val _errors = MutableSharedFlow<TunnelError>()
        private val _incomingSessions = MutableSharedFlow<MuxStream>(
            extraBufferCapacity = 0,
            replay = 0
        )

        override val state: StateFlow<TunnelState> = _state
        override val errors: SharedFlow<TunnelError> = _errors
        override val incomingSessions: Flow<MuxStream> = _incomingSessions

        override suspend fun connect(url: String) {
            _state.value = TunnelState.CONNECTED
        }

        override suspend fun disconnect() {
            _state.value = TunnelState.DISCONNECTED
        }

        /**
         * Emits a session to the collector. Suspends until a collector is ready,
         * ensuring no race between manager.start() and emitSession().
         */
        suspend fun emitSession(stream: MuxStream) {
            _incomingSessions.emit(stream)
        }

        /** Number of active subscribers. */
        val subscriberCount get() = _incomingSessions.subscriptionCount
    }

    // -- HTTP helpers --

    private fun httpPost(
        input: InputStream,
        output: OutputStream,
        path: String,
        body: String,
        bearerToken: String? = null
    ): String {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        sb.append("POST $path HTTP/1.1\r\n")
        sb.append("Host: test.rousecontext.com\r\n")
        sb.append("Content-Type: application/json\r\n")
        sb.append("Content-Length: ${bodyBytes.size}\r\n")
        sb.append("Connection: keep-alive\r\n")
        if (bearerToken != null) {
            sb.append("Authorization: Bearer $bearerToken\r\n")
        }
        sb.append("\r\n")

        output.write(sb.toString().toByteArray(Charsets.UTF_8))
        output.write(bodyBytes)
        output.flush()

        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        val statusLine = reader.readLine() ?: error("No status line received")
        assertTrue(statusLine.startsWith("HTTP/1.1"), "Expected HTTP response, got: $statusLine")

        var contentLength = -1
        var chunked = false
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val lower = line.lowercase()
            if (lower.startsWith("content-length:")) {
                contentLength = lower.substringAfter(":").trim().toInt()
            }
            if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                chunked = true
            }
        }

        return if (contentLength > 0) {
            val buf = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = reader.read(buf, read, contentLength - read)
                if (n == -1) break
                read += n
            }
            String(buf, 0, read)
        } else if (chunked) {
            readChunkedBody(reader)
        } else {
            ""
        }
    }

    private fun readChunkedBody(reader: BufferedReader): String {
        val sb = StringBuilder()
        while (true) {
            val sizeLine = reader.readLine() ?: break
            val chunkSize = sizeLine.trim().toInt(16)
            if (chunkSize == 0) {
                reader.readLine()
                break
            }
            val buf = CharArray(chunkSize)
            var read = 0
            while (read < chunkSize) {
                val n = reader.read(buf, read, chunkSize - read)
                if (n == -1) break
                read += n
            }
            sb.append(buf, 0, read)
            reader.readLine()
        }
        return sb.toString()
    }

    private fun mcpJsonRpc(method: String, params: String? = null, id: Int = 1): String {
        val paramsStr = if (params != null) ""","params":$params""" else ""
        return """{"jsonrpc":"2.0","method":"$method"$paramsStr,"id":$id}"""
    }
}
