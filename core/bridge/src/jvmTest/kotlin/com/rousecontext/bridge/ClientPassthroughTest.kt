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
 * Verifies the full client -> relay -> device passthrough path at the protocol level.
 *
 * Uses a [FakeTunnelClient] to simulate the relay/tunnel layer, wired to
 * [TunnelSessionManager] + [SessionHandler] for device-side handling.
 * The client side performs TLS handshake over channel-backed [MuxStream]s
 * and sends HTTP/JSON-RPC requests to the MCP server.
 *
 * The full data path exercised:
 *   Client HTTP -> TLS wrap -> MuxStream -> SessionHandler -> TLS accept ->
 *   TCP bridge -> Ktor MCP server -> response -> TCP bridge -> TLS wrap ->
 *   MuxStream -> TLS unwrap -> Client HTTP response
 */
class ClientPassthroughTest {

    private val mcpJson = Json { ignoreUnknownKeys = true }

    /**
     * Full passthrough: initialize MCP session, list tools, call a tool,
     * and verify the response traverses the entire path correctly.
     */
    @Test
    fun `full client to device passthrough with tool call`() = runBlocking {
        val certStore = TestCertHelper()
        val registry = InMemoryProviderRegistry()
        registry.register("test", EchoProvider())
        registry.setEnabled("test", true)
        val tokenStore = InMemoryTokenStore()
        val token = tokenStore.createTokenPair("test", "passthrough-client").accessToken

        val fakeTunnel = FakeTunnelClient()
        val handler = SessionHandler(
            certProvider = certStore,
            mcpSessionFactory = KtorMcpSessionFactory(registry, tokenStore)
        )
        val manager = TunnelSessionManager(fakeTunnel, handler, this)
        manager.start()

        // Wait for manager to subscribe before emitting sessions
        withTimeout(5_000) {
            fakeTunnel.subscriberCount.first { it > 0 }
        }

        // Simulate relay delivering a new mux stream (client connected)
        val (serverStream, clientStream) = createMuxPipe(1u)
        launch { fakeTunnel.emitSession(serverStream) }

        // Client side: TLS handshake over the mux pipe
        val clientIo = CompletableDeferred<Pair<InputStream, OutputStream>>()
        launch(Dispatchers.IO) {
            clientIo.complete(tlsClientHandshake(certStore, clientStream))
        }

        val (clientIn, clientOut) = withTimeout(10_000) { clientIo.await() }

        // Step 1: Initialize MCP session
        val initRequest = mcpJsonRpc(
            "initialize",
            """{"protocolVersion":"2025-03-26","capabilities":{},""" +
                """"clientInfo":{"name":"passthrough-test","version":"1.0"}}"""
        )
        val initResult = withTimeout(10_000) {
            httpPostFull(clientIn, clientOut, "/mcp", initRequest, token)
        }
        val initJson = mcpJson.parseToJsonElement(initResult.body).jsonObject
        assertEquals("2.0", initJson["jsonrpc"]?.jsonPrimitive?.content)
        val initResultObj = initJson["result"]?.jsonObject
        assertTrue(initResultObj != null, "Initialize should return a result")
        assertTrue(initResultObj.containsKey("protocolVersion"))
        assertTrue(initResultObj.containsKey("serverInfo"))
        val sessionId = initResult.sessionId

        // Step 2: List tools
        val listRequest = mcpJsonRpc("tools/list", id = 2)
        val listResponse = withTimeout(10_000) {
            httpPost(clientIn, clientOut, "/mcp", listRequest, token, sessionId)
        }
        val tools = mcpJson.parseToJsonElement(listResponse).jsonObject["result"]
            ?.jsonObject?.get("tools")?.jsonArray
        assertTrue(tools != null && tools.size == 1, "Should list one tool")
        assertEquals("echo", tools!![0].jsonObject["name"]?.jsonPrimitive?.content)

        // Step 3: Call tool and verify response
        val callRequest = mcpJsonRpc(
            "tools/call",
            """{"name":"echo","arguments":{"message":"passthrough works"}}""",
            id = 3
        )
        val callResponse = withTimeout(10_000) {
            httpPost(clientIn, clientOut, "/mcp", callRequest, token, sessionId)
        }
        val content = mcpJson.parseToJsonElement(callResponse).jsonObject["result"]
            ?.jsonObject?.get("content")?.jsonArray
        assertTrue(content != null && content.size == 1)
        assertEquals(
            "passthrough works",
            content!![0].jsonObject["text"]?.jsonPrimitive?.content,
            "Tool call response should pass through the full tunnel path"
        )

        manager.stop()
        coroutineContext.cancelChildren()
    }

    /**
     * Multiple sequential requests on the same passthrough session to verify
     * the connection stays alive and handles keep-alive correctly.
     */
    @Test
    fun `sequential requests on same passthrough session`() = runBlocking {
        val certStore = TestCertHelper()
        val registry = InMemoryProviderRegistry()
        registry.register("test", EchoProvider())
        registry.setEnabled("test", true)
        val tokenStore = InMemoryTokenStore()
        val token = tokenStore.createTokenPair("test", "passthrough-client").accessToken

        val fakeTunnel = FakeTunnelClient()
        val handler = SessionHandler(
            certProvider = certStore,
            mcpSessionFactory = KtorMcpSessionFactory(registry, tokenStore)
        )
        val manager = TunnelSessionManager(fakeTunnel, handler, this)
        manager.start()

        withTimeout(5_000) {
            fakeTunnel.subscriberCount.first { it > 0 }
        }

        val (serverStream, clientStream) = createMuxPipe(1u)
        launch { fakeTunnel.emitSession(serverStream) }

        val clientIo = CompletableDeferred<Pair<InputStream, OutputStream>>()
        launch(Dispatchers.IO) {
            clientIo.complete(tlsClientHandshake(certStore, clientStream))
        }

        val (clientIn, clientOut) = withTimeout(10_000) { clientIo.await() }

        // Initialize
        val initRequest = mcpJsonRpc(
            "initialize",
            """{"protocolVersion":"2025-03-26","capabilities":{},""" +
                """"clientInfo":{"name":"passthrough-test","version":"1.0"}}"""
        )
        val initResult = withTimeout(10_000) {
            httpPostFull(clientIn, clientOut, "/mcp", initRequest, token)
        }
        val sessionId = initResult.sessionId

        // Send 5 sequential tool calls on the same session
        val messages = listOf("first", "second", "third", "fourth", "fifth")
        for ((index, message) in messages.withIndex()) {
            val callRequest = mcpJsonRpc(
                "tools/call",
                """{"name":"echo","arguments":{"message":"$message"}}""",
                id = index + 2
            )
            val callResponse = withTimeout(10_000) {
                httpPost(clientIn, clientOut, "/mcp", callRequest, token, sessionId)
            }
            val text = mcpJson.parseToJsonElement(callResponse).jsonObject["result"]
                ?.jsonObject?.get("content")?.jsonArray
                ?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content
            assertEquals(
                message,
                text,
                "Request #${index + 1} should echo '$message' through the passthrough"
            )
        }

        manager.stop()
        coroutineContext.cancelChildren()
    }

    /**
     * Two concurrent passthrough sessions handled independently.
     * Each client gets its own mux stream and MCP session.
     */
    @Test
    fun `concurrent passthrough sessions are independent`() = runBlocking {
        val certStore = TestCertHelper()
        val registry = InMemoryProviderRegistry()
        registry.register("test", EchoProvider())
        registry.setEnabled("test", true)
        val tokenStore = InMemoryTokenStore()
        val token = tokenStore.createTokenPair("test", "passthrough-client").accessToken

        val fakeTunnel = FakeTunnelClient()
        val handler = SessionHandler(
            certProvider = certStore,
            mcpSessionFactory = KtorMcpSessionFactory(registry, tokenStore)
        )
        val manager = TunnelSessionManager(fakeTunnel, handler, this)
        manager.start()

        withTimeout(5_000) {
            fakeTunnel.subscriberCount.first { it > 0 }
        }

        // Two independent mux streams (two clients connecting through the relay)
        val (serverStream1, clientStream1) = createMuxPipe(1u)
        val (serverStream2, clientStream2) = createMuxPipe(2u)

        launch { fakeTunnel.emitSession(serverStream1) }
        launch { fakeTunnel.emitSession(serverStream2) }

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
                """"clientInfo":{"name":"passthrough-test","version":"1.0"}}"""
        )
        val initResult1 = withTimeout(10_000) {
            httpPostFull(clientIn1, clientOut1, "/mcp", initRequest, token)
        }
        val initResult2 = withTimeout(10_000) {
            httpPostFull(clientIn2, clientOut2, "/mcp", initRequest, token)
        }
        val sessionId1 = initResult1.sessionId
        val sessionId2 = initResult2.sessionId

        // Call tools on both streams with different messages
        val call1 = mcpJsonRpc(
            "tools/call",
            """{"name":"echo","arguments":{"message":"client-one"}}""",
            id = 2
        )
        val call2 = mcpJsonRpc(
            "tools/call",
            """{"name":"echo","arguments":{"message":"client-two"}}""",
            id = 2
        )

        val response1 = withTimeout(10_000) {
            httpPost(clientIn1, clientOut1, "/mcp", call1, token, sessionId1)
        }
        val response2 = withTimeout(10_000) {
            httpPost(clientIn2, clientOut2, "/mcp", call2, token, sessionId2)
        }

        val text1 = mcpJson.parseToJsonElement(response1).jsonObject["result"]
            ?.jsonObject?.get("content")?.jsonArray
            ?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content
        val text2 = mcpJson.parseToJsonElement(response2).jsonObject["result"]
            ?.jsonObject?.get("content")?.jsonArray
            ?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content

        assertEquals("client-one", text1, "Client 1 should get its own response")
        assertEquals("client-two", text2, "Client 2 should get its own response")

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

        override suspend fun sendFcmToken(token: String) {
            // no-op for tests
        }

        override suspend fun healthCheck(timeout: kotlin.time.Duration): Boolean = true

        suspend fun emitSession(stream: MuxStream) {
            _incomingSessions.emit(stream)
        }

        val subscriberCount get() = _incomingSessions.subscriptionCount
    }

    // -- HTTP helpers --

    /**
     * Result of a raw HTTP POST, carrying the response body and any
     * `Mcp-Session-Id` header returned by the server.
     */
    private data class HttpResult(val body: String, val sessionId: String? = null)

    private fun httpPost(
        input: InputStream,
        output: OutputStream,
        path: String,
        body: String,
        bearerToken: String? = null,
        sessionId: String? = null
    ): String = httpPostFull(input, output, path, body, bearerToken, sessionId).body

    @Suppress("LongParameterList")
    private fun httpPostFull(
        input: InputStream,
        output: OutputStream,
        path: String,
        body: String,
        bearerToken: String? = null,
        sessionId: String? = null
    ): HttpResult {
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
        if (sessionId != null) {
            sb.append("Mcp-Session-Id: $sessionId\r\n")
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
        var mcpSessionId: String? = null
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
            if (lower.startsWith("mcp-session-id:")) {
                mcpSessionId = line.substringAfter(":").trim()
            }
        }

        val responseBody = if (contentLength > 0) {
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
        return HttpResult(responseBody, mcpSessionId)
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
