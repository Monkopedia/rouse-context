package com.rousecontext.bridge

import com.rousecontext.mcp.core.DeviceCodeManager
import com.rousecontext.mcp.core.InMemoryProviderRegistry
import com.rousecontext.mcp.core.InMemoryTokenStore
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import javax.net.ssl.SSLContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tests for [SessionHandler] verifying the full data path:
 * MuxStream -> TLS accept -> plaintext HTTP -> MCP session -> JSON-RPC response.
 */
class SessionHandlerTest {

    private val mcpJson = Json { ignoreUnknownKeys = true }

    @Test
    fun `handleStream completes TLS and serves MCP initialize`() = runBlocking {
        val certStore = TestCertHelper()
        val registry = InMemoryProviderRegistry()
        registry.register("test", EchoProvider())
        registry.setEnabled("test", true)
        val tokenStore = InMemoryTokenStore()
        val token = tokenStore.createTokenPair("test", "bridge-test-client").accessToken

        val handler = SessionHandler(
            certProvider = certStore,
            mcpSessionFactory = KtorMcpSessionFactory(registry, tokenStore)
        )

        val (serverStream, clientStream) = createMuxPipe(1u)

        // Server side: SessionHandler handles the stream
        launch(Dispatchers.IO) {
            handler.handleStream(serverStream)
        }

        // Client side: TLS handshake
        val clientIo = CompletableDeferred<Pair<InputStream, OutputStream>>()
        launch(Dispatchers.IO) {
            clientIo.complete(tlsClientHandshake(certStore, clientStream))
        }

        val (clientIn, clientOut) = withTimeout(10_000) { clientIo.await() }

        // Send MCP initialize
        val initRequest = mcpJsonRpc(
            "initialize",
            """{"protocolVersion":"2025-03-26","capabilities":{},""" +
                """"clientInfo":{"name":"bridge-test","version":"1.0"}}"""
        )
        val responseBody = withTimeout(10_000) {
            httpPost(clientIn, clientOut, "/mcp", initRequest, token)
        }

        val json = mcpJson.parseToJsonElement(responseBody).jsonObject
        assertEquals("2.0", json["jsonrpc"]?.jsonPrimitive?.content)
        val result = json["result"]?.jsonObject
        assertTrue(result != null, "Expected result in response, got: $responseBody")
        assertTrue(result.containsKey("protocolVersion"))
        assertTrue(result.containsKey("serverInfo"))

        coroutineContext.cancelChildren()
    }

    @Test
    fun `full tool call flow through SessionHandler`() = runBlocking {
        val certStore = TestCertHelper()
        val registry = InMemoryProviderRegistry()
        registry.register("test", EchoProvider())
        registry.setEnabled("test", true)
        val tokenStore = InMemoryTokenStore()
        val token = tokenStore.createTokenPair("test", "bridge-test-client").accessToken

        val handler = SessionHandler(
            certProvider = certStore,
            mcpSessionFactory = KtorMcpSessionFactory(registry, tokenStore)
        )

        val (serverStream, clientStream) = createMuxPipe(1u)

        launch(Dispatchers.IO) {
            handler.handleStream(serverStream)
        }

        val clientIo = CompletableDeferred<Pair<InputStream, OutputStream>>()
        launch(Dispatchers.IO) {
            clientIo.complete(tlsClientHandshake(certStore, clientStream))
        }

        val (clientIn, clientOut) = withTimeout(10_000) { clientIo.await() }

        // Initialize
        val initRequest = mcpJsonRpc(
            "initialize",
            """{"protocolVersion":"2025-03-26","capabilities":{},""" +
                """"clientInfo":{"name":"bridge-test","version":"1.0"}}"""
        )
        withTimeout(10_000) {
            httpPost(clientIn, clientOut, "/mcp", initRequest, token)
        }

        // tools/list
        val listRequest = mcpJsonRpc("tools/list", id = 2)
        val listResponse = withTimeout(10_000) {
            httpPost(clientIn, clientOut, "/mcp", listRequest, token)
        }
        val tools = mcpJson.parseToJsonElement(listResponse).jsonObject["result"]
            ?.jsonObject?.get("tools")?.jsonArray
        assertTrue(tools != null && tools.size == 1, "Should list one tool")
        assertEquals("echo", tools!![0].jsonObject["name"]?.jsonPrimitive?.content)

        // tools/call
        val callRequest = mcpJsonRpc(
            "tools/call",
            """{"name":"echo","arguments":{"message":"hello from bridge"}}""",
            id = 3
        )
        val callResponse = withTimeout(10_000) {
            httpPost(clientIn, clientOut, "/mcp", callRequest, token)
        }
        val content = mcpJson.parseToJsonElement(callResponse).jsonObject["result"]
            ?.jsonObject?.get("content")?.jsonArray
        assertTrue(content != null && content.size == 1)
        assertEquals(
            "hello from bridge",
            content!![0].jsonObject["text"]?.jsonPrimitive?.content
        )

        coroutineContext.cancelChildren()
    }

    @Test
    fun `multiple providers routed by path prefix`() = runBlocking {
        val certStore = TestCertHelper()
        val registry = InMemoryProviderRegistry()
        registry.register("health", HealthProvider())
        registry.setEnabled("health", true)
        registry.register("test", EchoProvider())
        registry.setEnabled("test", true)
        val tokenStore = InMemoryTokenStore()
        val healthToken = tokenStore.createTokenPair("health", "bridge-test-client").accessToken
        val testToken = tokenStore.createTokenPair("test", "bridge-test-client").accessToken

        val handler = SessionHandler(
            certProvider = certStore,
            mcpSessionFactory = KtorMcpSessionFactory(registry, tokenStore)
        )

        val (serverStream, clientStream) = createMuxPipe(1u)

        launch(Dispatchers.IO) {
            handler.handleStream(serverStream)
        }

        val clientIo = CompletableDeferred<Pair<InputStream, OutputStream>>()
        launch(Dispatchers.IO) {
            clientIo.complete(tlsClientHandshake(certStore, clientStream))
        }

        val (clientIn, clientOut) = withTimeout(10_000) { clientIo.await() }

        // Initialize health provider
        val initRequest = mcpJsonRpc(
            "initialize",
            """{"protocolVersion":"2025-03-26","capabilities":{},""" +
                """"clientInfo":{"name":"bridge-test","version":"1.0"}}"""
        )
        withTimeout(10_000) {
            httpPost(clientIn, clientOut, "/health/mcp", initRequest, healthToken)
        }

        // Call health tool
        val healthCall = mcpJsonRpc(
            "tools/call",
            """{"name":"get_steps","arguments":{}}""",
            id = 2
        )
        val healthResponse = withTimeout(10_000) {
            httpPost(clientIn, clientOut, "/health/mcp", healthCall, healthToken)
        }
        val healthContent = mcpJson.parseToJsonElement(healthResponse).jsonObject["result"]
            ?.jsonObject?.get("content")?.jsonArray
        assertEquals(
            "10000 steps",
            healthContent?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content
        )

        // Initialize test provider on the same stream
        withTimeout(10_000) {
            httpPost(clientIn, clientOut, "/mcp", initRequest, testToken)
        }

        // Call echo tool
        val echoCall = mcpJsonRpc(
            "tools/call",
            """{"name":"echo","arguments":{"message":"multi-provider"}}""",
            id = 3
        )
        val echoResponse = withTimeout(10_000) {
            httpPost(clientIn, clientOut, "/mcp", echoCall, testToken)
        }
        val echoContent = mcpJson.parseToJsonElement(echoResponse).jsonObject["result"]
            ?.jsonObject?.get("content")?.jsonArray
        assertEquals(
            "multi-provider",
            echoContent?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content
        )

        coroutineContext.cancelChildren()
    }

    @Test
    fun `OAuth device code flow through bridge`() = runBlocking {
        val certStore = TestCertHelper()
        val registry = InMemoryProviderRegistry()
        registry.register("test", EchoProvider())
        registry.setEnabled("test", true)
        val tokenStore = InMemoryTokenStore()
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)

        val handler = SessionHandler(
            certProvider = certStore,
            mcpSessionFactory = KtorMcpSessionFactory(
                registry,
                tokenStore,
                deviceCodeManager
            )
        )

        val (serverStream, clientStream) = createMuxPipe(1u)

        launch(Dispatchers.IO) {
            handler.handleStream(serverStream)
        }

        val clientIo = CompletableDeferred<Pair<InputStream, OutputStream>>()
        launch(Dispatchers.IO) {
            clientIo.complete(tlsClientHandshake(certStore, clientStream))
        }

        val (clientIn, clientOut) = withTimeout(10_000) { clientIo.await() }

        // Step 1: Request without token should get 401
        val initRequest = mcpJsonRpc(
            "initialize",
            """{"protocolVersion":"2025-03-26","capabilities":{},""" +
                """"clientInfo":{"name":"bridge-test","version":"1.0"}}"""
        )
        val unauthorizedStatus = withTimeout(10_000) {
            httpPostStatus(clientIn, clientOut, "/mcp", initRequest, bearerToken = null)
        }
        assertEquals(401, unauthorizedStatus)

        // Step 2: Start device code flow
        val authorizeBody = """{"client_id":"test-client"}"""
        val authorizeResponse = withTimeout(10_000) {
            httpPost(clientIn, clientOut, "/device/authorize", authorizeBody)
        }
        val authorizeJson = mcpJson.parseToJsonElement(authorizeResponse).jsonObject
        val deviceCode = authorizeJson["device_code"]?.jsonPrimitive?.content
        val userCode = authorizeJson["user_code"]?.jsonPrimitive?.content
        assertTrue(deviceCode != null, "Expected device_code")
        assertTrue(userCode != null, "Expected user_code")

        // Step 3: Poll before approval should be pending
        val grantType = "urn:ietf:params:oauth:grant-type:device_code"
        val pollBody = """{"device_code":"$deviceCode","grant_type":"$grantType"}"""
        val pendingResponse = withTimeout(10_000) {
            httpPost(clientIn, clientOut, "/token", pollBody)
        }
        assertTrue(
            pendingResponse.contains("authorization_pending"),
            "Expected authorization_pending, got: $pendingResponse"
        )

        // Step 4: Approve via device code manager
        deviceCodeManager.approve(userCode!!)

        // Step 5: Poll again should get token
        val approvedResponse = withTimeout(10_000) {
            httpPost(clientIn, clientOut, "/token", pollBody)
        }
        val approvedJson = mcpJson.parseToJsonElement(approvedResponse).jsonObject
        val accessToken = approvedJson["access_token"]?.jsonPrimitive?.content
        assertTrue(accessToken != null, "Expected access_token, got: $approvedResponse")

        // Step 6: Use token to make MCP request
        val responseBody = withTimeout(10_000) {
            httpPost(clientIn, clientOut, "/mcp", initRequest, accessToken)
        }
        val result = mcpJson.parseToJsonElement(responseBody).jsonObject["result"]?.jsonObject
        assertTrue(result != null, "Expected result")
        assertTrue(result.containsKey("protocolVersion"))

        coroutineContext.cancelChildren()
    }

    @Test
    fun `handleStream throws when no cert available`() = runBlocking {
        val handler = SessionHandler(
            certProvider = NullCertProvider(),
            mcpSessionFactory = object : McpSessionFactory {
                override suspend fun create(): McpSessionHandle = error("Should not be called")
            }
        )

        val (serverStream, _) = createMuxPipe(1u)

        val result = runCatching {
            handler.handleStream(serverStream)
        }

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull() is IllegalStateException,
            "Expected IllegalStateException"
        )

        coroutineContext.cancelChildren()
    }

    // -- Test helpers --

    private class NullCertProvider : TlsCertProvider {
        override fun serverSslContext(): SSLContext? = null
    }

    // -- Test HTTP helpers (extracted from integration tests) --

    private fun httpPost(
        input: InputStream,
        output: OutputStream,
        path: String,
        body: String,
        bearerToken: String? = null
    ): String {
        val (_, responseBody) = httpPostRaw(input, output, path, body, bearerToken)
        return responseBody
    }

    private fun httpPostStatus(
        input: InputStream,
        output: OutputStream,
        path: String,
        body: String,
        bearerToken: String? = null
    ): Int {
        val (statusCode, _) = httpPostRaw(input, output, path, body, bearerToken)
        return statusCode
    }

    private fun httpPostRaw(
        input: InputStream,
        output: OutputStream,
        path: String,
        body: String,
        bearerToken: String? = null
    ): Pair<Int, String> {
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
        val statusCode = statusLine.split(" ")[1].toInt()

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

        return statusCode to responseBody
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
