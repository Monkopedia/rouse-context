package com.rousecontext.tunnel.integration

import com.rousecontext.bridge.McpSessionFactory
import com.rousecontext.bridge.McpSessionHandle
import com.rousecontext.bridge.SessionHandler
import com.rousecontext.bridge.TlsCertProvider
import com.rousecontext.mcp.core.AuthorizationCodeManager
import com.rousecontext.mcp.core.DeviceCodeManager
import com.rousecontext.mcp.core.InMemoryProviderRegistry
import com.rousecontext.mcp.core.InMemoryTokenStore
import com.rousecontext.mcp.core.McpServerProvider
import com.rousecontext.mcp.core.configureMcpRouting
import com.rousecontext.mcp.core.generateInternalToken
import com.rousecontext.tunnel.MuxStream
import com.rousecontext.tunnel.TunnelClientImpl
import com.rousecontext.tunnel.TunnelState
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Multi-client concurrency integration test (issue #186).
 *
 * Opens two simultaneous AI-client TLS connections through a single tunnel,
 * verifies they get independent MCP sessions (different Mcp-Session-Id),
 * closes one and asserts the other stays usable, then opens a third client
 * and verifies it works. Catches the #179-family stream-tracking bugs and
 * cross-contamination in the bridge's response routing.
 *
 * Uses real relay binary, real TLS, real mux protocol, real OAuth + MCP flow.
 */
@Suppress("LargeClass")
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultiClientConcurrencyTest {

    companion object {
        private const val RELAY_HOSTNAME = "localhost"
        private const val DEVICE_SUBDOMAIN = "multi-client-device"
        private const val INTEGRATION = "test"
        private const val INTEGRATION_HOST =
            "exact-$INTEGRATION.$DEVICE_SUBDOMAIN.$RELAY_HOSTNAME"
        private const val SESSION_REGISTRATION_DELAY_MS = 500L
    }

    private lateinit var tempDir: File
    private lateinit var ca: TestCertificateAuthority
    private lateinit var relayManager: TestRelayManager
    private var relayPort: Int = 0

    private val caCert: X509Certificate get() = ca.caCert
    private val deviceKeyStore: KeyStore get() = ca.deviceKeyStore
    private val deviceCert: X509Certificate get() = ca.deviceCert

    private val mcpJson = Json { ignoreUnknownKeys = true }

    private lateinit var authorizationCodeManager: AuthorizationCodeManager
    private lateinit var tokenStore: InMemoryTokenStore
    private lateinit var tunnelClient: TunnelClientImpl

    private val backgroundScope = CoroutineScope(Dispatchers.IO)

    @BeforeAll
    fun setUp() = runBlocking {
        val relayBinary = findRelayBinary()
        assumeTrue(
            relayBinary.exists() && relayBinary.canExecute(),
            "Relay binary not found. Build with: cd relay && cargo build"
        )

        tempDir = File.createTempFile("e2e-multi-client-", "")
        tempDir.delete()
        tempDir.mkdirs()

        ca = TestCertificateAuthority(tempDir, RELAY_HOSTNAME, DEVICE_SUBDOMAIN)
        ca.generate()

        relayManager = TestRelayManager(tempDir, RELAY_HOSTNAME)
        relayPort = findFreePort()
        relayManager.start(relayPort)

        val registry = InMemoryProviderRegistry()
        registry.register("test", EchoProvider())
        registry.setEnabled("test", true)
        tokenStore = InMemoryTokenStore()
        authorizationCodeManager = AuthorizationCodeManager(tokenStore = tokenStore)

        tunnelClient = connectTunnelClient()

        backgroundScope.launch {
            tunnelClient.incomingSessions.collect { stream ->
                launch(Dispatchers.IO) {
                    handleDeviceSession(stream, registry, tokenStore)
                }
            }
        }
        Unit
    }

    @AfterAll
    fun tearDown() {
        if (::tunnelClient.isInitialized) {
            runBlocking { tunnelClient.disconnect() }
        }
        backgroundScope.cancel()
        if (::relayManager.isInitialized) {
            relayManager.stop()
        }
        if (::tempDir.isInitialized && tempDir.exists()) {
            tempDir.deleteRecursively()
        }
    }

    // =====================================================================
    // Test: two concurrent clients, independent disconnect, third client
    // =====================================================================

    @Test
    @Suppress("LongMethod")
    fun `two concurrent clients with independent disconnect and third client`() = runBlocking {
        // --- Phase 1: open two concurrent AI clients ---
        val client1 = withTimeout(15_000) { openAiClient() }
        // Small delay so the relay/mux fully registers the first stream
        delay(500)
        val client2 = withTimeout(15_000) { openAiClient() }
        // Allow device-side session handlers to spin up
        delay(500)

        // Full OAuth + MCP initialize on both, sequentially to avoid
        // BufferedReader interleaving issues (each client has its own
        // socket, but the device session handlers need time to start).
        val session1 = withTimeout(30_000) { performOAuthAndInitialize(client1, "client-1") }
        val session2 = withTimeout(30_000) { performOAuthAndInitialize(client2, "client-2") }

        // Assert: different Mcp-Session-Id values
        assertNotNull(session1.mcpSessionId, "Client 1 should have an Mcp-Session-Id")
        assertNotNull(session2.mcpSessionId, "Client 2 should have an Mcp-Session-Id")
        assertNotEquals(
            session1.mcpSessionId,
            session2.mcpSessionId,
            "Two concurrent clients must get different Mcp-Session-Id values"
        )

        // Assert: tunnel is ACTIVE (at least one stream open)
        assertEquals(
            TunnelState.ACTIVE,
            tunnelClient.state.value,
            "Tunnel should be ACTIVE with two open streams"
        )

        // --- Phase 2: verify no cross-contamination ---
        val echo1 = callEcho(client1, session1, "message-for-client-1")
        val echo2 = callEcho(client2, session2, "message-for-client-2")
        assertEquals("message-for-client-1", echo1, "Client 1 got wrong response")
        assertEquals("message-for-client-2", echo2, "Client 2 got wrong response")

        // --- Phase 3: close client 1, verify client 2 still works ---
        client1.socket.close()

        // Give the relay/mux layer time to propagate the CLOSE frame
        delay(1_000)

        // Client 2 should still be usable
        val echo2After = callEcho(client2, session2, "still-alive")
        assertEquals("still-alive", echo2After, "Client 2 should still work after client 1 closed")

        // Tunnel should still be ACTIVE (one stream remains)
        assertEquals(
            TunnelState.ACTIVE,
            tunnelClient.state.value,
            "Tunnel should remain ACTIVE with one stream still open"
        )

        // --- Phase 4: open a third client on the same tunnel ---
        val client3 = withTimeout(15_000) { openAiClient() }
        val session3 = withTimeout(30_000) { performOAuthAndInitialize(client3, "client-3") }

        assertNotNull(session3.mcpSessionId, "Client 3 should have an Mcp-Session-Id")
        assertNotEquals(
            session2.mcpSessionId,
            session3.mcpSessionId,
            "Client 3 must get a different Mcp-Session-Id than client 2"
        )

        // tools/list on client 3 should succeed
        val toolsResponse = httpRequest(
            client3.input,
            client3.output,
            method = "POST",
            path = "/mcp",
            body = mcpJsonRpc("tools/list", id = nextId()),
            bearerToken = session3.accessToken,
            mcpSessionId = session3.mcpSessionId
        )
        assertEquals(200, toolsResponse.statusCode, "Client 3 tools/list should succeed")
        val tools = mcpJson.parseToJsonElement(toolsResponse.body)
            .jsonObject["result"]?.jsonObject?.get("tools")?.jsonArray
        assertNotNull(tools, "Client 3 should see tools")
        assertTrue(tools!!.isNotEmpty(), "Client 3 should have at least one tool")

        // Echo on client 3
        val echo3 = callEcho(client3, session3, "third-client-works")
        assertEquals("third-client-works", echo3, "Client 3 echo should work")

        // --- Phase 5: close remaining clients ---
        client2.socket.close()
        client3.socket.close()

        // Wait for CLOSE propagation
        withTimeout(5_000) {
            while (tunnelClient.state.value == TunnelState.ACTIVE) {
                delay(100)
            }
        }
        assertEquals(
            TunnelState.CONNECTED,
            tunnelClient.state.value,
            "Tunnel should return to CONNECTED after all streams close"
        )
    }

    // =====================================================================
    // AI client connection + session state
    // =====================================================================

    private data class AiClient(
        val socket: SSLSocket,
        val input: java.io.InputStream,
        val output: java.io.OutputStream
    )

    private data class ClientSession(
        val mcpSessionId: String?,
        val accessToken: String,
        val refreshToken: String?
    )

    private suspend fun openAiClient(): AiClient {
        val socket = CompletableDeferred<SSLSocket>()
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            socket.complete(connectAiClient())
        }
        val s = socket.await()
        return AiClient(s, s.inputStream, s.outputStream)
    }

    /**
     * Performs the full Claude-style OAuth flow and MCP initialize on a single
     * AI client connection. Returns the session state needed for subsequent calls.
     */
    @Suppress("LongMethod")
    private fun performOAuthAndInitialize(client: AiClient, clientName: String): ClientSession {
        var idCounter = 1

        // Step 1: Dynamic client registration
        val registerBody = buildJsonObject {
            put("client_name", JsonPrimitive("multi-client-test-$clientName"))
            put(
                "redirect_uris",
                kotlinx.serialization.json.JsonArray(
                    listOf(JsonPrimitive("http://localhost:3000/callback"))
                )
            )
        }.toString()

        val registerResponse = httpRequest(
            client.input,
            client.output,
            method = "POST",
            path = "/register",
            body = registerBody
        )
        assertEquals(
            201,
            registerResponse.statusCode,
            "$clientName: register failed: ${registerResponse.body}"
        )
        val clientId = mcpJson.parseToJsonElement(registerResponse.body)
            .jsonObject["client_id"]?.jsonPrimitive?.content
        assertNotNull(clientId, "$clientName: registration should return client_id")

        // Step 2: PKCE authorize
        val codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)
        val state = "state-$clientName-${System.nanoTime()}"

        val authorizePath = "/authorize" +
            "?response_type=code" +
            "&client_id=$clientId" +
            "&code_challenge=$codeChallenge" +
            "&code_challenge_method=S256" +
            "&redirect_uri=${urlEncode("http://localhost:3000/callback")}" +
            "&state=$state"

        val authorizeResponse = httpRequest(
            client.input,
            client.output,
            method = "GET",
            path = authorizePath
        )
        assertEquals(200, authorizeResponse.statusCode, "$clientName: authorize failed")

        val displayCodeRegex = Regex("""class="code">([A-Z2-9]{6}-[A-Z2-9]{6})<""")
        val displayCode = displayCodeRegex.find(authorizeResponse.body)
            ?.groupValues?.get(1)
        assertNotNull(displayCode, "$clientName: should find display code in HTML")

        val requestIdRegex = Regex("request_id=([a-f0-9-]+)")
        val requestId = requestIdRegex.find(authorizeResponse.body)
            ?.groupValues?.get(1)
        assertNotNull(requestId, "$clientName: should find request_id in HTML")

        // Step 3: Approve
        val approved = authorizationCodeManager.approve(displayCode!!)
        assertTrue(approved, "$clientName: approve should succeed")

        // Step 4: Poll status -> get auth code
        val statusResponse = httpRequest(
            client.input,
            client.output,
            method = "GET",
            path = "/authorize/status?request_id=$requestId"
        )
        val statusJson = mcpJson.parseToJsonElement(statusResponse.body).jsonObject
        val authCode = statusJson["code"]?.jsonPrimitive?.content
        assertNotNull(authCode, "$clientName: should contain auth code")

        // Step 5: Token exchange
        val tokenBody = "grant_type=authorization_code" +
            "&code=$authCode" +
            "&code_verifier=$codeVerifier"
        val tokenResponse = httpRequest(
            client.input,
            client.output,
            method = "POST",
            path = "/token",
            body = tokenBody,
            contentType = "application/x-www-form-urlencoded"
        )
        assertEquals(
            200,
            tokenResponse.statusCode,
            "$clientName: token exchange failed: ${tokenResponse.body}"
        )
        val tokenJson = mcpJson.parseToJsonElement(tokenResponse.body).jsonObject
        val accessToken = tokenJson["access_token"]?.jsonPrimitive?.content!!
        val refreshToken = tokenJson["refresh_token"]?.jsonPrimitive?.content

        // Step 6: MCP initialize
        val initResponse = httpRequest(
            client.input,
            client.output,
            method = "POST",
            path = "/mcp",
            body = mcpJsonRpc("initialize", initializeParams(), id = idCounter++),
            bearerToken = accessToken
        )
        assertEquals(
            200,
            initResponse.statusCode,
            "$clientName: initialize failed: ${initResponse.body}"
        )
        val mcpSessionId = initResponse.headers["mcp-session-id"]

        val initJson = mcpJson.parseToJsonElement(initResponse.body).jsonObject
        assertNotNull(
            initJson["result"]?.jsonObject,
            "$clientName: initialize must return a result"
        )

        // Send initialized notification
        httpRequest(
            client.input,
            client.output,
            method = "POST",
            path = "/mcp",
            body = """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
            bearerToken = accessToken,
            mcpSessionId = mcpSessionId
        )

        return ClientSession(mcpSessionId, accessToken, refreshToken)
    }

    private fun callEcho(client: AiClient, session: ClientSession, message: String): String {
        val response = httpRequest(
            client.input,
            client.output,
            method = "POST",
            path = "/mcp",
            body = mcpJsonRpc(
                "tools/call",
                """{"name":"echo","arguments":{"message":"$message"}}""",
                id = nextId()
            ),
            bearerToken = session.accessToken,
            mcpSessionId = session.mcpSessionId
        )
        assertEquals(200, response.statusCode, "Echo call should succeed for message: $message")
        return mcpJson.parseToJsonElement(response.body)
            .jsonObject["result"]
            ?.jsonObject?.get("content")
            ?.jsonArray?.get(0)
            ?.jsonObject?.get("text")
            ?.jsonPrimitive?.content
            ?: error("No text content in echo response: ${response.body}")
    }

    // =====================================================================
    // MCP server provider
    // =====================================================================

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
                val message =
                    request.params.arguments?.get("message")
                        ?.jsonPrimitive?.content ?: "empty"
                CallToolResult(content = listOf(TextContent(message)))
            }
        }
    }

    // =====================================================================
    // Device session handling
    // =====================================================================

    private suspend fun handleDeviceSession(
        stream: MuxStream,
        registry: InMemoryProviderRegistry,
        tokenStore: InMemoryTokenStore
    ) {
        val deviceSslContext = TestSslContexts.buildDeviceServer(deviceKeyStore)
        val certProvider = object : TlsCertProvider {
            override suspend fun serverSslContext(): SSLContext = deviceSslContext
        }
        val factory = object : McpSessionFactory {
            override suspend fun create(): McpSessionHandle {
                val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)
                val token = generateInternalToken()
                val server = embeddedServer(CIO, port = 0) {
                    configureMcpRouting(
                        registry = registry,
                        tokenStore = tokenStore,
                        deviceCodeManager = deviceCodeManager,
                        authorizationCodeManager = authorizationCodeManager,
                        hostname = INTEGRATION_HOST,
                        integration = INTEGRATION,
                        internalToken = token
                    )
                }
                server.start(wait = false)
                val port = server.engine.resolvedConnectors().first().port
                return McpSessionHandle(
                    port = port,
                    internalToken = token,
                    stop = { server.stop() }
                )
            }
        }
        val handler = SessionHandler(certProvider = certProvider, mcpSessionFactory = factory)
        handler.handleStream(stream)
    }

    // =====================================================================
    // TunnelClient connection
    // =====================================================================

    private suspend fun connectTunnelClient(): TunnelClientImpl {
        val sslContext = TestSslContexts.buildMtls(deviceKeyStore, caCert)
        val wsFactory = MtlsWebSocketFactory(sslContext)
        val client = TunnelClientImpl(
            scope = CoroutineScope(Dispatchers.IO),
            webSocketFactory = wsFactory
        )
        client.connect("wss://$RELAY_HOSTNAME:$relayPort/ws")
        assertEquals(
            TunnelState.CONNECTED,
            client.state.value,
            "TunnelClient should be CONNECTED"
        )
        delay(SESSION_REGISTRATION_DELAY_MS)
        return client
    }

    // =====================================================================
    // AI client connection
    // =====================================================================

    private fun connectAiClient(): SSLSocket {
        val factory = TestSslContexts.buildAiClientSocketFactory(caCert, deviceCert)
        val socket = factory.createSocket(
            "127.0.0.1",
            relayPort
        ) as SSLSocket
        socket.soTimeout = 30_000
        val params = socket.sslParameters
        params.serverNames = listOf(javax.net.ssl.SNIHostName(INTEGRATION_HOST))
        socket.sslParameters = params
        socket.startHandshake()
        return socket
    }

    // =====================================================================
    // HTTP helpers
    // =====================================================================

    private data class HttpResponse(
        val statusCode: Int,
        val headers: Map<String, String>,
        val body: String
    )

    private var requestIdCounter = 100

    private fun nextId(): Int = ++requestIdCounter

    @Suppress(
        "LongParameterList",
        "LongMethod",
        "CyclomaticComplexMethod",
        "LoopWithTooManyJumpStatements"
    )
    private fun httpRequest(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        method: String,
        path: String,
        body: String? = null,
        bearerToken: String? = null,
        contentType: String = "application/json",
        mcpSessionId: String? = null
    ): HttpResponse {
        val sb = StringBuilder()
        sb.append("$method $path HTTP/1.1\r\n")
        sb.append("Host: $INTEGRATION_HOST\r\n")

        if (body != null) {
            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            sb.append("Content-Type: $contentType\r\n")
            sb.append("Content-Length: ${bodyBytes.size}\r\n")
        }
        sb.append("Connection: keep-alive\r\n")
        if (bearerToken != null) {
            sb.append("Authorization: Bearer $bearerToken\r\n")
        }
        if (mcpSessionId != null) {
            sb.append("Mcp-Session-Id: $mcpSessionId\r\n")
        }
        sb.append("\r\n")

        output.write(sb.toString().toByteArray(Charsets.UTF_8))
        if (body != null) {
            output.write(body.toByteArray(Charsets.UTF_8))
        }
        output.flush()

        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        val statusLine = reader.readLine() ?: error("No status line received")
        assertTrue(
            statusLine.startsWith("HTTP/1.1"),
            "Expected HTTP response, got: $statusLine"
        )
        val statusCode = statusLine.split(" ")[1].toInt()

        val headers = mutableMapOf<String, String>()
        var contentLength = -1
        var chunked = false
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val lower = line.lowercase()
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                headers[line.substring(0, colonIdx).lowercase()] =
                    line.substring(colonIdx + 1).trim()
            }
            if (lower.startsWith("content-length:")) {
                contentLength = lower.substringAfter(":").trim().toInt()
            }
            if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                chunked = true
            }
        }

        val responseBody = when {
            contentLength > 0 -> {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(buf, read, contentLength - read)
                    if (n == -1) break
                    read += n
                }
                String(buf, 0, read)
            }
            chunked -> readChunkedBody(reader)
            contentLength == 0 -> ""
            else -> ""
        }

        return HttpResponse(statusCode, headers, responseBody)
    }

    @Suppress("LoopWithTooManyJumpStatements")
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

    // =====================================================================
    // JSON-RPC helpers
    // =====================================================================

    private fun mcpJsonRpc(method: String, params: String? = null, id: Int = 1): String {
        val paramsStr = if (params != null) ""","params":$params""" else ""
        return """{"jsonrpc":"2.0","method":"$method"$paramsStr,"id":$id}"""
    }

    private fun initializeParams(): String =
        """{"protocolVersion":"2025-03-26","capabilities":{},""" +
            """"clientInfo":{"name":"multi-client-test","version":"1.0"}}"""

    // =====================================================================
    // PKCE helpers
    // =====================================================================

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}
