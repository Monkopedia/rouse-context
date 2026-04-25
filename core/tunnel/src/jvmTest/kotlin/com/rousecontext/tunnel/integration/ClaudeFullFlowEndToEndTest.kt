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
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
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
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.Timeout

/**
 * Claude-style full-flow end-to-end test exercising every step a real
 * MCP client (e.g. Claude Desktop) performs, in order:
 *
 * 1. Protected-resource metadata discovery
 * 2. Authorization-server metadata discovery
 * 3. Dynamic client registration
 * 4. PKCE authorize + user approval + redirect
 * 5. Token exchange
 * 6. MCP initialize
 * 7. tools/list
 * 8. tools/call echo
 * 9. Refresh token rotation (new token works, old token rejected)
 *
 * Uses real relay binary, real TLS, real mux protocol. No phone, no adb,
 * no Firebase. The "user approval" is injected via AuthorizationCodeManager.
 *
 * State flows across ordered test methods so we exercise the full session
 * lifecycle as a single Claude-like client would.
 */
@Suppress("LargeClass")
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ClaudeFullFlowEndToEndTest {

    companion object {
        private const val RELAY_HOSTNAME = "localhost"
        private const val DEVICE_SUBDOMAIN = "claude-flow-device"
        private const val INTEGRATION = "test"
        private const val INTEGRATION_HOST =
            "exact-$INTEGRATION.$DEVICE_SUBDOMAIN.$RELAY_HOSTNAME"

        /**
         * Upper bound for [TestRelayManager.waitForSessionRegistered]. The
         * relay-side `Notify` typically fires within a millisecond of the
         * mux WebSocket upgrade completing; 10s is generous for CI under
         * stress (#400).
         */
        private const val SESSION_REGISTRATION_TIMEOUT_MS = 10_000L
    }

    // --- Infrastructure (shared across all test methods) ---
    private lateinit var tempDir: File
    private lateinit var ca: TestCertificateAuthority
    private lateinit var relayManager: TestRelayManager
    private var relayPort: Int = 0

    private val caCert: X509Certificate get() = ca.caCert
    private val deviceKeyStore: KeyStore get() = ca.deviceKeyStore
    private val deviceCert: X509Certificate get() = ca.deviceCert

    private val mcpJson = Json { ignoreUnknownKeys = true }

    private lateinit var authorizationCodeManager: AuthorizationCodeManager
    private var mcpSessionId: String? = null
    private lateinit var tokenStore: InMemoryTokenStore
    private lateinit var tunnelClient: TunnelClientImpl
    private lateinit var aiSocket: SSLSocket
    private lateinit var aiInput: java.io.InputStream
    private lateinit var aiOutput: java.io.OutputStream

    /** Scope for the session collector and background work that outlives setUp. */
    private val backgroundScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)

    // --- State flowing across ordered test methods ---
    private var clientId: String? = null
    private var accessToken: String? = null
    private var refreshToken: String? = null
    private var requestIdCounter = 1

    @BeforeAll
    fun setUp() = runBlocking {
        val relayBinary = findRelayBinary()
        assumeTrue(
            relayBinary.exists() && relayBinary.canExecute(),
            "Relay binary not found. Build with: cd relay && cargo build"
        )

        tempDir = File.createTempFile("e2e-claude-flow-", "")
        tempDir.delete()
        tempDir.mkdirs()

        ca = TestCertificateAuthority(tempDir, RELAY_HOSTNAME, DEVICE_SUBDOMAIN)
        ca.generate()

        // test-mode enables the `/test/wait-session-registered` admin
        // endpoint used by `connectTunnelClient` to deterministically wait
        // for the relay-side `SessionRegistry.insert` instead of a blind
        // 500ms sleep (#400, #402).
        relayManager = TestRelayManager(tempDir, RELAY_HOSTNAME, enableTestMode = true)
        relayPort = findFreePort()
        relayManager.start(relayPort)

        val registry = InMemoryProviderRegistry()
        registry.register("test", EchoProvider())
        registry.setEnabled("test", true)
        tokenStore = InMemoryTokenStore()
        authorizationCodeManager = AuthorizationCodeManager(tokenStore = tokenStore)

        // Subscribe to incoming sessions BEFORE connect so the collector is
        // active when the relay starts splicing AI-client sockets to mux
        // streams. Matches production wiring in TunnelForegroundService
        // (#402): `_incomingSessions` is a `Channel(Channel.BUFFERED)`, so a
        // late subscriber wouldn't lose frames, but the previous
        // connect-then-collect ordering masked subtler races where the
        // collector hadn't started before the AI-client TLS handshake hit
        // the relay (~1-in-7 flake on iter 7/50 of the stress loop).
        tunnelClient = createTunnelClient()
        backgroundScope.launch {
            tunnelClient.incomingSessions.collect { stream ->
                launch(Dispatchers.IO) {
                    handleDeviceSession(stream, registry, tokenStore)
                }
            }
        }
        connectTunnelClient(tunnelClient)

        // AI client connects via TLS through the relay
        aiSocket = withTimeout(15_000) {
            CompletableDeferred<SSLSocket>().also { deferred ->
                launch(Dispatchers.IO) {
                    deferred.complete(connectAiClient())
                }
            }.await()
        }
        aiInput = aiSocket.inputStream
        aiOutput = aiSocket.outputStream
    }

    @AfterAll
    fun tearDown() {
        if (::aiSocket.isInitialized) {
            runCatching { aiSocket.close() }
        }
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
    // Step 1: Protected-resource metadata discovery
    // =====================================================================

    @Test
    @Order(1)
    fun `step 1 - discover protected-resource metadata`() {
        val response = httpRequest(
            aiInput,
            aiOutput,
            method = "GET",
            path = "/.well-known/oauth-protected-resource/mcp"
        )
        assertEquals(
            200,
            response.statusCode,
            "Protected-resource metadata should return 200"
        )
        val meta = mcpJson.parseToJsonElement(response.body).jsonObject
        assertNotNull(meta["resource"], "Should contain resource field")
        val servers = meta["authorization_servers"]?.jsonArray
        assertNotNull(servers, "Should contain authorization_servers array")
        assertTrue(servers!!.isNotEmpty(), "authorization_servers must not be empty")
    }

    // =====================================================================
    // Step 2: Authorization-server metadata discovery
    // =====================================================================

    @Test
    @Order(2)
    fun `step 2 - discover authorization-server metadata`() {
        val response = httpRequest(
            aiInput,
            aiOutput,
            method = "GET",
            path = "/.well-known/oauth-authorization-server"
        )
        assertEquals(200, response.statusCode)
        val metadata = mcpJson.parseToJsonElement(response.body).jsonObject
        assertNotNull(
            metadata["authorization_endpoint"],
            "Should have authorization_endpoint"
        )
        assertNotNull(
            metadata["token_endpoint"],
            "Should have token_endpoint"
        )
        assertNotNull(
            metadata["registration_endpoint"],
            "Should have registration_endpoint"
        )
    }

    // =====================================================================
    // Step 3: Dynamic client registration
    // =====================================================================

    @Test
    @Order(3)
    fun `step 3 - dynamic client registration`() {
        val registerBody = buildJsonObject {
            put("client_name", JsonPrimitive("claude-e2e-test-client"))
            put(
                "redirect_uris",
                kotlinx.serialization.json.JsonArray(
                    listOf(JsonPrimitive("http://localhost:3000/callback"))
                )
            )
        }.toString()

        val response = httpRequest(
            aiInput,
            aiOutput,
            method = "POST",
            path = "/register",
            body = registerBody
        )
        assertEquals(
            201,
            response.statusCode,
            "Register should return 201, got body: ${response.body}"
        )
        val json = mcpJson.parseToJsonElement(response.body).jsonObject
        clientId = json["client_id"]?.jsonPrimitive?.content
        assertNotNull(clientId, "Registration should return client_id")
    }

    // =====================================================================
    // Steps 4-5: PKCE authorize -> approve -> token exchange
    // =====================================================================

    @Test
    @Order(4)
    @Suppress("LongMethod")
    fun `step 4-5 - PKCE authorize, approve, and token exchange`() {
        val codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)
        val state = "test-state-${System.nanoTime()}"

        // GET /authorize with PKCE
        val authorizePath = "/authorize" +
            "?response_type=code" +
            "&client_id=$clientId" +
            "&code_challenge=$codeChallenge" +
            "&code_challenge_method=S256" +
            "&redirect_uri=${urlEncode("http://localhost:3000/callback")}" +
            "&state=$state"

        val authorizeResponse = httpRequest(
            aiInput,
            aiOutput,
            method = "GET",
            path = authorizePath
        )
        assertEquals(200, authorizeResponse.statusCode, "Authorize should return 200")

        // Extract request_id and display code from HTML
        val requestIdRegex = Regex("request_id=([a-f0-9-]+)")
        val requestIdMatch = requestIdRegex.find(authorizeResponse.body)
        assertNotNull(requestIdMatch, "Should find request_id in HTML")
        val requestId = requestIdMatch!!.groupValues[1]

        val displayCodeRegex = Regex("""class="code">([A-Z2-9]{6}-[A-Z2-9]{6})<""")
        val displayCodeMatch = displayCodeRegex.find(authorizeResponse.body)
        assertNotNull(displayCodeMatch, "Should find display code in HTML")
        val displayCode = displayCodeMatch!!.groupValues[1]

        // Verify pending status
        val pendingResponse = httpRequest(
            aiInput,
            aiOutput,
            method = "GET",
            path = "/authorize/status?request_id=$requestId"
        )
        assertEquals(200, pendingResponse.statusCode)
        assertEquals(
            "pending",
            mcpJson.parseToJsonElement(pendingResponse.body)
                .jsonObject["status"]?.jsonPrimitive?.content
        )

        // Approve (simulates user tapping "Approve" on phone)
        val approved = authorizationCodeManager.approve(displayCode)
        assertTrue(approved, "Approve should succeed")

        // Poll status -> approved
        val approvedResponse = httpRequest(
            aiInput,
            aiOutput,
            method = "GET",
            path = "/authorize/status?request_id=$requestId"
        )
        assertEquals(200, approvedResponse.statusCode)
        val approvedJson = mcpJson.parseToJsonElement(approvedResponse.body).jsonObject
        assertEquals("approved", approvedJson["status"]?.jsonPrimitive?.content)
        val authCode = approvedJson["code"]?.jsonPrimitive?.content
        assertNotNull(authCode, "Should contain auth code")
        assertEquals(state, approvedJson["state"]?.jsonPrimitive?.content)

        // Exchange code for tokens
        val tokenBody = "grant_type=authorization_code" +
            "&code=$authCode" +
            "&code_verifier=$codeVerifier"
        val tokenResponse = httpRequest(
            aiInput,
            aiOutput,
            method = "POST",
            path = "/token",
            body = tokenBody,
            contentType = "application/x-www-form-urlencoded"
        )
        assertEquals(
            200,
            tokenResponse.statusCode,
            "Token exchange should succeed, got: ${tokenResponse.body}"
        )
        val tokenJson = mcpJson.parseToJsonElement(tokenResponse.body).jsonObject
        accessToken = tokenJson["access_token"]?.jsonPrimitive?.content
        refreshToken = tokenJson["refresh_token"]?.jsonPrimitive?.content
        assertNotNull(accessToken, "Should have access_token")
        assertNotNull(refreshToken, "Should have refresh_token")
        assertEquals("Bearer", tokenJson["token_type"]?.jsonPrimitive?.content)
    }

    // =====================================================================
    // Step 6: MCP initialize
    // =====================================================================

    @Test
    @Order(5)
    fun `step 6 - MCP initialize returns result with protocolVersion`() {
        val response = httpRequest(
            aiInput,
            aiOutput,
            method = "POST",
            path = "/mcp",
            body = mcpJsonRpc("initialize", initializeParams()),
            bearerToken = accessToken
        )
        assertEquals(200, response.statusCode, "Authenticated initialize should succeed")
        mcpSessionId = response.headers["mcp-session-id"]
        assertNotNull(mcpSessionId, "Initialize must return Mcp-Session-Id header (#189)")
        val json = mcpJson.parseToJsonElement(response.body).jsonObject
        assertEquals("2.0", json["jsonrpc"]?.jsonPrimitive?.content)
        val result = json["result"]?.jsonObject
        assertNotNull(result, "Initialize must return a result field (caught #181)")
        assertTrue(
            result!!.containsKey("protocolVersion"),
            "Result should contain protocolVersion"
        )
        assertTrue(
            result.containsKey("serverInfo"),
            "Result should contain serverInfo"
        )

        // Send initialized notification (required by MCP protocol)
        httpRequest(
            aiInput,
            aiOutput,
            method = "POST",
            path = "/mcp",
            body = """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
            bearerToken = accessToken
        )
    }

    // =====================================================================
    // Step 7: tools/list
    // =====================================================================

    @Test
    @Order(6)
    fun `step 7 - tools list returns registered tools`() {
        val response = httpRequest(
            aiInput,
            aiOutput,
            method = "POST",
            path = "/mcp",
            body = mcpJsonRpc("tools/list", id = nextId()),
            bearerToken = accessToken
        )
        assertEquals(200, response.statusCode)
        val json = mcpJson.parseToJsonElement(response.body).jsonObject
        val tools = json["result"]?.jsonObject?.get("tools")?.jsonArray
        assertNotNull(tools, "tools/list should return tools array")
        assertTrue(tools!!.isNotEmpty(), "Should have at least one tool")
        assertEquals("echo", tools[0].jsonObject["name"]?.jsonPrimitive?.content)
    }

    // =====================================================================
    // Step 8: tools/call echo
    // =====================================================================

    @Test
    @Order(7)
    fun `step 8 - tools call echo round-trips message`() {
        val response = httpRequest(
            aiInput,
            aiOutput,
            method = "POST",
            path = "/mcp",
            body = mcpJsonRpc(
                "tools/call",
                """{"name":"echo","arguments":{"message":"hello from Claude e2e"}}""",
                id = nextId()
            ),
            bearerToken = accessToken
        )
        assertEquals(200, response.statusCode)
        val json = mcpJson.parseToJsonElement(response.body).jsonObject
        val content = json["result"]?.jsonObject?.get("content")?.jsonArray
        assertNotNull(content, "tools/call should return content")
        assertEquals(
            "hello from Claude e2e",
            content!![0].jsonObject["text"]?.jsonPrimitive?.content
        )
    }

    // =====================================================================
    // Step 9: Refresh token rotation
    // =====================================================================

    @Test
    @Order(8)
    fun `step 9 - refresh token rotation issues new tokens`() {
        val oldAccessToken = accessToken!!
        val oldRefreshToken = refreshToken!!

        // Exchange refresh token for new token pair
        val refreshBody = "grant_type=refresh_token" +
            "&refresh_token=$oldRefreshToken"
        val refreshResponse = httpRequest(
            aiInput,
            aiOutput,
            method = "POST",
            path = "/token",
            body = refreshBody,
            contentType = "application/x-www-form-urlencoded"
        )
        assertEquals(
            200,
            refreshResponse.statusCode,
            "Refresh should succeed, got: ${refreshResponse.body}"
        )
        val refreshJson = mcpJson.parseToJsonElement(refreshResponse.body).jsonObject
        val newAccessToken = refreshJson["access_token"]?.jsonPrimitive?.content
        val newRefreshToken = refreshJson["refresh_token"]?.jsonPrimitive?.content
        assertNotNull(newAccessToken, "Should issue new access_token")
        assertNotNull(newRefreshToken, "Should issue new refresh_token")
        assertTrue(
            newAccessToken != oldAccessToken,
            "New access token must differ from old"
        )
        assertTrue(
            newRefreshToken != oldRefreshToken,
            "New refresh token must differ from old"
        )

        // New access token works
        val goodResponse = httpRequest(
            aiInput,
            aiOutput,
            method = "POST",
            path = "/mcp",
            body = mcpJsonRpc("tools/list", id = nextId()),
            bearerToken = newAccessToken
        )
        assertEquals(
            200,
            goodResponse.statusCode,
            "New access token should be accepted"
        )

        // Old access token is rejected (rotation revokes it)
        val rejectedResponse = httpRequest(
            aiInput,
            aiOutput,
            method = "POST",
            path = "/mcp",
            body = mcpJsonRpc("tools/list", id = nextId()),
            bearerToken = oldAccessToken
        )
        assertEquals(
            401,
            rejectedResponse.statusCode,
            "Old access token should be rejected after rotation"
        )

        // Old refresh token is rejected (single-use rotation)
        val replayResponse = httpRequest(
            aiInput,
            aiOutput,
            method = "POST",
            path = "/token",
            body = "grant_type=refresh_token&refresh_token=$oldRefreshToken",
            contentType = "application/x-www-form-urlencoded"
        )
        assertEquals(
            400,
            replayResponse.statusCode,
            "Replayed refresh token should be rejected (catches #170)"
        )

        // Update state for potential future test methods
        accessToken = newAccessToken
        refreshToken = newRefreshToken
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

    /**
     * Build the [TunnelClientImpl] without connecting it. The caller is
     * expected to subscribe to [TunnelClientImpl.incomingSessions] before
     * invoking [connectTunnelClient] so the collector is live when the
     * relay starts splicing client sockets onto mux streams.
     */
    private fun createTunnelClient(): TunnelClientImpl {
        val sslContext = TestSslContexts.buildMtls(deviceKeyStore, caCert)
        val wsFactory = MtlsWebSocketFactory(sslContext)
        return TunnelClientImpl(
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO),
            webSocketFactory = wsFactory
        )
    }

    private suspend fun connectTunnelClient(client: TunnelClientImpl) {
        client.connect("wss://$RELAY_HOSTNAME:$relayPort/ws")
        assertEquals(
            com.rousecontext.tunnel.TunnelState.CONNECTED,
            client.state.value,
            "TunnelClient should be CONNECTED"
        )
        // Deterministic wait for the relay's `SessionRegistry.insert` after
        // the WS upgrade completes (#400). Backed by per-subdomain `Notify`
        // on the relay, exposed via the test-mode admin endpoint.
        val registered = relayManager.waitForSessionRegistered(
            DEVICE_SUBDOMAIN,
            SESSION_REGISTRATION_TIMEOUT_MS
        )
        assertTrue(
            registered,
            "Relay did not register mux session for $DEVICE_SUBDOMAIN within " +
                "${SESSION_REGISTRATION_TIMEOUT_MS}ms"
        )
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

    @Suppress("LongParameterList")
    private fun httpRequest(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        method: String,
        path: String,
        body: String? = null,
        bearerToken: String? = null,
        contentType: String = "application/json"
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
        if (mcpSessionId != null && path == "/mcp") {
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

    private fun readChunkedBody(reader: BufferedReader): String {
        val sb = StringBuilder()
        while (true) {
            val sizeLine = reader.readLine() ?: break
            val chunkSize = sizeLine.trim().toInt(16)
            if (chunkSize == 0) {
                reader.readLine() // trailing CRLF
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
            reader.readLine() // chunk trailing CRLF
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
            """"clientInfo":{"name":"claude-e2e-test","version":"1.0"}}"""

    private fun nextId(): Int = ++requestIdCounter

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
