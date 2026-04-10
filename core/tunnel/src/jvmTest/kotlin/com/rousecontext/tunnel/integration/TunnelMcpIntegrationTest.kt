package com.rousecontext.tunnel.integration

import com.rousecontext.bridge.McpSessionFactory
import com.rousecontext.bridge.McpSessionHandle
import com.rousecontext.bridge.SessionHandler
import com.rousecontext.bridge.TlsCertProvider
import com.rousecontext.mcp.core.DeviceCodeManager
import com.rousecontext.mcp.core.InMemoryProviderRegistry
import com.rousecontext.mcp.core.InMemoryTokenStore
import com.rousecontext.mcp.core.McpServerProvider
import com.rousecontext.mcp.core.configureMcpRouting
import com.rousecontext.tunnel.ChannelMuxStream
import com.rousecontext.tunnel.TestCertificateStore
import com.rousecontext.tunnel.TlsClientInputStream
import com.rousecontext.tunnel.TlsClientOutputStream
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngineResult
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * End-to-end integration tests verifying the full data path:
 * MuxDemux OPEN frame -> TLS accept -> plaintext streams -> MCP HTTP server -> JSON-RPC response.
 *
 * Uses [SessionHandler] from core:bridge for the server-side TLS accept + MCP bridging.
 * Tests IDs 97-99 from overall.md.
 */
@Tag("integration")
class TunnelMcpIntegrationTest {

    private val mcpJson = Json { ignoreUnknownKeys = true }

    /**
     * A test MCP provider that registers an "echo" tool.
     */
    private class EchoProvider : McpServerProvider {
        override val id = "test"
        override val displayName = "Test Integration"

        override fun register(server: Server) {
            server.addTool(
                name = "echo",
                description = "Echoes back the input message",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        put(
                            "message",
                            buildJsonObject {
                                put("type", JsonPrimitive("string"))
                            }
                        )
                    },
                    required = listOf("message")
                )
            ) { request ->
                val message = request.params.arguments
                    ?.get("message")?.jsonPrimitive?.content
                    ?: "empty"
                CallToolResult(content = listOf(TextContent(message)))
            }
        }
    }

    /**
     * Wraps [TestCertificateStore] as a [TlsCertProvider] for [SessionHandler].
     */
    private class TestCertAdapter(
        private val certStore: TestCertificateStore
    ) : TlsCertProvider {
        override fun serverSslContext(): SSLContext = certStore.sslContext
    }

    /**
     * [McpSessionFactory] that starts a Ktor MCP server on an ephemeral port.
     */
    private class TestMcpSessionFactory(
        private val registry: InMemoryProviderRegistry,
        private val tokenStore: InMemoryTokenStore
    ) : McpSessionFactory {
        override suspend fun create(): McpSessionHandle {
            val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)
            val server = embeddedServer(CIO, port = 0) {
                configureMcpRouting(
                    registry = registry,
                    tokenStore = tokenStore,
                    deviceCodeManager = deviceCodeManager,
                    hostname = "test.rousecontext.com",
                    integration = "test"
                )
            }
            server.start(wait = false)
            val port = server.engine.resolvedConnectors().first().port
            return McpSessionHandle(port = port, stop = { server.stop() })
        }
    }

    private fun createSessionHandler(
        certStore: TestCertificateStore,
        registry: InMemoryProviderRegistry,
        tokenStore: InMemoryTokenStore
    ): SessionHandler {
        return SessionHandler(
            certProvider = TestCertAdapter(certStore),
            mcpSessionFactory = TestMcpSessionFactory(registry, tokenStore)
        )
    }

    /**
     * Creates a connected pair of ChannelMuxStreams simulating a mux pipe.
     */
    private fun createMuxPipe(streamId: UInt): Pair<ChannelMuxStream, ChannelMuxStream> {
        val serverToClient = Channel<ByteArray>(Channel.BUFFERED)
        val clientToServer = Channel<ByteArray>(Channel.BUFFERED)

        val serverStream = ChannelMuxStream(
            streamIdValue = streamId,
            readChannel = clientToServer,
            writeChannel = serverToClient
        )
        val clientStream = ChannelMuxStream(
            streamIdValue = streamId,
            readChannel = serverToClient,
            writeChannel = clientToServer
        )

        return serverStream to clientStream
    }

    /**
     * Performs TLS client-side handshake and returns plaintext I/O streams.
     */
    private suspend fun tlsClientHandshake(
        certStore: TestCertificateStore,
        clientStream: ChannelMuxStream
    ): Pair<InputStream, OutputStream> {
        val sslEngine = certStore.trustingSslContext.createSSLEngine(
            "test.rousecontext.com",
            443
        )
        sslEngine.useClientMode = true

        val session = sslEngine.session
        var netIn = java.nio.ByteBuffer.allocate(session.packetBufferSize)
        val netOut = java.nio.ByteBuffer.allocate(session.packetBufferSize)
        val appIn = java.nio.ByteBuffer.allocate(session.applicationBufferSize)
        val appOut = java.nio.ByteBuffer.allocate(session.applicationBufferSize)

        sslEngine.beginHandshake()
        var hsStatus = sslEngine.handshakeStatus

        while (hsStatus != SSLEngineResult.HandshakeStatus.FINISHED &&
            hsStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
        ) {
            when (hsStatus) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    netOut.clear()
                    val result = sslEngine.wrap(appOut, netOut)
                    hsStatus = result.handshakeStatus
                    netOut.flip()
                    if (netOut.hasRemaining()) {
                        val data = ByteArray(netOut.remaining())
                        netOut.get(data)
                        clientStream.send(data)
                    }
                }
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    val tlsData = clientStream.read()
                    netIn = ensureCapacity(netIn, tlsData.size)
                    netIn.put(tlsData)
                    netIn.flip()
                    val result = sslEngine.unwrap(netIn, appIn)
                    hsStatus = result.handshakeStatus
                    netIn.compact()
                }
                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    var task = sslEngine.delegatedTask
                    while (task != null) {
                        task.run()
                        task = sslEngine.delegatedTask
                    }
                    hsStatus = sslEngine.handshakeStatus
                }
                else -> break
            }
        }

        return Pair(
            TlsClientInputStream(sslEngine, clientStream, netIn),
            TlsClientOutputStream(sslEngine, clientStream)
        )
    }

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

    /**
     * Test ID 97: OPEN frame -> SessionHandler (TLS accept + MCP bridge) ->
     * client sends initialize -> gets valid response.
     */
    @Test
    fun `OPEN frame through TLS tunnel to MCP initialize returns valid response`() = runBlocking {
        val certStore = TestCertificateStore()

        val registry = InMemoryProviderRegistry()
        registry.register("test", EchoProvider())
        registry.setEnabled("test", true)
        val tokenStore = InMemoryTokenStore()
        val token = tokenStore.createTokenPair("test", "integration-client").accessToken

        val handler = createSessionHandler(certStore, registry, tokenStore)

        val (serverStream, clientStream) = createMuxPipe(1u)

        // Server side: SessionHandler handles TLS accept + MCP bridge
        launch(Dispatchers.IO) {
            handler.handleStream(serverStream)
        }

        // Client side: TLS handshake
        val clientIo = CompletableDeferred<Pair<InputStream, OutputStream>>()
        launch(Dispatchers.IO) {
            val io = tlsClientHandshake(certStore, clientStream)
            clientIo.complete(io)
        }

        val (clientIn, clientOut) = withTimeout(10_000) { clientIo.await() }

        val initRequest = mcpJsonRpc(
            "initialize",
            """{"protocolVersion":"2025-03-26","capabilities":{}""" +
                ""","clientInfo":{"name":"integration-test","version":"1.0"}}"""
        )

        val responseBody = withTimeout(10_000) {
            httpPost(clientIn, clientOut, "/test/mcp", initRequest, token)
        }

        val json = mcpJson.parseToJsonElement(responseBody).jsonObject
        assertEquals("2.0", json["jsonrpc"]?.jsonPrimitive?.content)
        val result = json["result"]?.jsonObject
        assertTrue(result != null, "Expected result in response, got: $responseBody")
        assertTrue(
            result!!.containsKey("protocolVersion"),
            "Expected protocolVersion in result"
        )
        assertTrue(
            result.containsKey("serverInfo"),
            "Expected serverInfo in result"
        )

        coroutineContext.cancelChildren()
    }

    /**
     * Test ID 98: Full tool call flow through tunnel: initialize -> tools/list -> tools/call.
     */
    @Test
    fun `full tool call flow through TLS tunnel`() = runBlocking {
        val certStore = TestCertificateStore()

        val registry = InMemoryProviderRegistry()
        registry.register("test", EchoProvider())
        registry.setEnabled("test", true)
        val tokenStore = InMemoryTokenStore()
        val token = tokenStore.createTokenPair("test", "integration-client").accessToken

        val handler = createSessionHandler(certStore, registry, tokenStore)

        val (serverStream, clientStream) = createMuxPipe(1u)

        launch(Dispatchers.IO) {
            handler.handleStream(serverStream)
        }

        val clientIo = CompletableDeferred<Pair<InputStream, OutputStream>>()
        launch(Dispatchers.IO) {
            val io = tlsClientHandshake(certStore, clientStream)
            clientIo.complete(io)
        }

        val (clientIn, clientOut) = withTimeout(10_000) { clientIo.await() }

        // Step 1: Initialize
        val initRequest = mcpJsonRpc(
            "initialize",
            """{"protocolVersion":"2025-03-26","capabilities":{}""" +
                ""","clientInfo":{"name":"integration-test","version":"1.0"}}"""
        )
        val initResponse = withTimeout(10_000) {
            httpPost(clientIn, clientOut, "/test/mcp", initRequest, token)
        }
        val initJson = mcpJson.parseToJsonElement(initResponse).jsonObject
        assertTrue(
            initJson["result"]?.jsonObject?.containsKey("protocolVersion") == true,
            "Initialize should succeed"
        )

        // Step 2: tools/list
        val listRequest = mcpJsonRpc("tools/list", id = 2)
        val listResponse = withTimeout(10_000) {
            httpPost(clientIn, clientOut, "/test/mcp", listRequest, token)
        }
        val listJson = mcpJson.parseToJsonElement(listResponse).jsonObject
        val tools = listJson["result"]?.jsonObject?.get("tools")?.jsonArray
        assertTrue(tools != null && tools.size == 1, "Should list one tool")
        assertEquals(
            "echo",
            tools!![0].jsonObject["name"]?.jsonPrimitive?.content
        )

        // Step 3: tools/call
        val callRequest = mcpJsonRpc(
            "tools/call",
            """{"name":"echo","arguments":{"message":"hello through tunnel"}}""",
            id = 3
        )
        val callResponse = withTimeout(10_000) {
            httpPost(clientIn, clientOut, "/test/mcp", callRequest, token)
        }
        val callJson = mcpJson.parseToJsonElement(callResponse).jsonObject
        val content = callJson["result"]?.jsonObject?.get("content")?.jsonArray
        assertTrue(content != null && content.size == 1, "Should have one content item")
        assertEquals(
            "hello through tunnel",
            content!![0].jsonObject["text"]?.jsonPrimitive?.content
        )

        coroutineContext.cancelChildren()
    }

    /**
     * Test ID 99: Two concurrent OPEN frames create two independent MCP sessions
     * that both serve requests independently through the tunnel.
     */
    @Test
    fun `concurrent streams serve independent MCP sessions through tunnel`() = runBlocking {
        val certStore = TestCertificateStore()

        val registry = InMemoryProviderRegistry()
        registry.register("test", EchoProvider())
        registry.setEnabled("test", true)
        val tokenStore = InMemoryTokenStore()
        val token = tokenStore.createTokenPair("test", "integration-client").accessToken

        val handler = createSessionHandler(certStore, registry, tokenStore)

        // Create two independent mux stream pairs (simulating two OPEN frames)
        val (serverStream1, clientStream1) = createMuxPipe(1u)
        val (serverStream2, clientStream2) = createMuxPipe(2u)

        // Server side: SessionHandler handles both streams
        launch(Dispatchers.IO) {
            handler.handleStream(serverStream1)
        }
        launch(Dispatchers.IO) {
            handler.handleStream(serverStream2)
        }

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
            """{"protocolVersion":"2025-03-26","capabilities":{}""" +
                ""","clientInfo":{"name":"integration-test","version":"1.0"}}"""
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

        coroutineContext.cancelChildren()
    }

    private fun ensureCapacity(
        buffer: java.nio.ByteBuffer,
        additionalBytes: Int
    ): java.nio.ByteBuffer {
        if (buffer.remaining() >= additionalBytes) return buffer
        val newBuffer = java.nio.ByteBuffer.allocate(buffer.position() + additionalBytes)
        buffer.flip()
        newBuffer.put(buffer)
        return newBuffer
    }
}
