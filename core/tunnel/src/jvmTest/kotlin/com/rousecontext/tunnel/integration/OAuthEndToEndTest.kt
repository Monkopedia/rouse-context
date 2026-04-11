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
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * End-to-end test that exercises the full OAuth authorization code flow
 * with PKCE through the real Rust relay binary.
 *
 * Data path: AI client -> TLS -> relay (SNI passthrough) -> mux WebSocket ->
 *            device TunnelClientImpl -> SessionHandler (TLS accept) ->
 *            Ktor MCP server (OAuth + JSON-RPC).
 *
 * Skipped if the relay binary has not been built.
 * Build it with: cd relay && cargo build
 */
@Suppress("LargeClass")
@Tag("integration")
class OAuthEndToEndTest {

    companion object {
        private const val RELAY_HOSTNAME = "localhost"
        private const val DEVICE_SUBDOMAIN = "test-device"
        private const val INTEGRATION = "test"
        private const val INTEGRATION_HOST = "exact-$INTEGRATION.$DEVICE_SUBDOMAIN.$RELAY_HOSTNAME"
    }

    private lateinit var tempDir: File
    private lateinit var ca: TestCertificateAuthority
    private lateinit var relayManager: TestRelayManager
    private var relayPort: Int = 0

    private val caCert: X509Certificate get() = ca.caCert
    private val deviceKeyStore: KeyStore get() = ca.deviceKeyStore
    private val deviceCert: X509Certificate get() = ca.deviceCert

    private val mcpJson = Json { ignoreUnknownKeys = true }

    // Shared state between the MCP server and the test --
    // the test calls approve() on this after the AI client hits /authorize.
    private lateinit var authorizationCodeManager: AuthorizationCodeManager

    @BeforeEach
    fun setUp() {
        val relayBinary = findRelayBinary()
        assumeTrue(
            relayBinary.exists() && relayBinary.canExecute(),
            "Relay binary not found. Build with: cd relay && cargo build"
        )

        tempDir = File.createTempFile("e2e-oauth-", "")
        tempDir.delete()
        tempDir.mkdirs()

        ca = TestCertificateAuthority(tempDir, RELAY_HOSTNAME, DEVICE_SUBDOMAIN)
        ca.generate()

        relayManager = TestRelayManager(tempDir, RELAY_HOSTNAME)
        relayPort = findFreePort()
        relayManager.start(relayPort)
    }

    @AfterEach
    fun tearDown() {
        if (::relayManager.isInitialized) {
            relayManager.stop()
        }
        if (::tempDir.isInitialized && tempDir.exists()) {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Full OAuth authorization code flow with PKCE through the relay:
     *
     * 1. Device connects via TunnelClientImpl with mTLS
     * 2. AI client connects via TLS to device subdomain through relay
     * 3. POST /mcp without auth -> 401 Unauthorized (Host header routes to integration)
     * 4. GET /.well-known/oauth-authorization-server -> OAuth metadata
     * 5. POST /register -> dynamic client registration -> client_id
     * 6. GET /authorize with PKCE challenge -> HTML with display code
     * 7. Programmatic approve via authorizationCodeManager.approve()
     * 8. GET /authorize/status -> approved with auth code
     * 9. POST /token with code + code_verifier -> access_token
     * 10. POST /mcp with Bearer token + initialize -> 200
     * 11. POST /mcp with tools/list -> tool list
     * 12. POST /mcp with tools/call echo -> echo response
     */
    @Suppress("LongMethod")
    @Test
    fun `full OAuth authorization code flow with PKCE through relay`() = runBlocking {
        val registry = InMemoryProviderRegistry()
        registry.register("test", EchoProvider())
        registry.setEnabled("test", true)
        val tokenStore = InMemoryTokenStore()
        authorizationCodeManager = AuthorizationCodeManager(tokenStore = tokenStore)

        val tunnelClient = connectTunnelClient()

        try {
            // Start collecting sessions in background
            val sessionHandlerDone = CompletableDeferred<Unit>()
            val collectJob = launch {
                tunnelClient.incomingSessions.collect { stream ->
                    launch(Dispatchers.IO) {
                        handleDeviceSession(stream, registry, tokenStore)
                        sessionHandlerDone.complete(Unit)
                    }
                }
            }

            // AI client connects via TLS through the relay
            val aiSocket = withTimeout(15_000) {
                CompletableDeferred<SSLSocket>().also { deferred ->
                    launch(Dispatchers.IO) {
                        deferred.complete(connectAiClient())
                    }
                }.await()
            }

            val input = aiSocket.inputStream
            val output = aiSocket.outputStream

            // --- Step 1: POST /mcp without auth -> 401 ---
            val unauthResponse = httpRequest(
                input,
                output,
                method = "POST",
                path = "/mcp",
                body = mcpJsonRpc("initialize", initializeParams())
            )
            assertEquals(
                401,
                unauthResponse.statusCode,
                "MCP without auth should return 401, got: ${unauthResponse.statusCode}"
            )

            // --- Step 2: Discover OAuth metadata ---
            val metadataResponse = httpRequest(
                input,
                output,
                method = "GET",
                path = "/.well-known/oauth-authorization-server"
            )
            assertEquals(200, metadataResponse.statusCode)
            val metadata = mcpJson.parseToJsonElement(metadataResponse.body).jsonObject
            assertNotNull(metadata["authorization_endpoint"])
            assertNotNull(metadata["token_endpoint"])
            assertNotNull(metadata["registration_endpoint"])

            // --- Step 3: Dynamic client registration ---
            val registerBody = buildJsonObject {
                put("client_name", JsonPrimitive("integration-test-client"))
                put(
                    "redirect_uris",
                    kotlinx.serialization.json.JsonArray(
                        listOf(JsonPrimitive("http://localhost:3000/callback"))
                    )
                )
            }.toString()
            val registerResponse = httpRequest(
                input,
                output,
                method = "POST",
                path = "/register",
                body = registerBody
            )
            assertEquals(
                201,
                registerResponse.statusCode,
                "Register should return 201, got body: ${registerResponse.body}"
            )
            val registerJson = mcpJson.parseToJsonElement(registerResponse.body).jsonObject
            val clientId = registerJson["client_id"]?.jsonPrimitive?.content
            assertNotNull(clientId, "Registration should return client_id")

            // --- Step 4: Generate PKCE challenge ---
            val codeVerifier = generateCodeVerifier()
            val codeChallenge = generateCodeChallenge(codeVerifier)

            // --- Step 5: GET /authorize with PKCE ---
            val state = "test-state-${System.nanoTime()}"
            val authorizePath = "/authorize" +
                "?response_type=code" +
                "&client_id=$clientId" +
                "&code_challenge=$codeChallenge" +
                "&code_challenge_method=S256" +
                "&redirect_uri=${urlEncode("http://localhost:3000/callback")}" +
                "&state=$state"
            val authorizeResponse = httpRequest(
                input,
                output,
                method = "GET",
                path = authorizePath
            )
            assertEquals(
                200,
                authorizeResponse.statusCode,
                "Authorize should return 200 with HTML"
            )
            assertTrue(
                authorizeResponse.body.contains("Rouse Context"),
                "Authorize response should contain HTML page"
            )

            // Extract the request_id from the HTML (it is in the status polling URL)
            val requestIdRegex = Regex("request_id=([a-f0-9-]+)")
            val requestIdMatch = requestIdRegex.find(authorizeResponse.body)
            assertNotNull(requestIdMatch, "Should find request_id in HTML")
            val requestId = requestIdMatch!!.groupValues[1]

            // Extract the display code from the HTML
            val displayCodeRegex = Regex("""class="code">([A-Z2-9]{6}-[A-Z2-9]{6})<""")
            val displayCodeMatch = displayCodeRegex.find(authorizeResponse.body)
            assertNotNull(displayCodeMatch, "Should find display code in HTML")
            val displayCode = displayCodeMatch!!.groupValues[1]

            // --- Step 6: Verify status is pending ---
            val pendingResponse = httpRequest(
                input,
                output,
                method = "GET",
                path = "/authorize/status?request_id=$requestId"
            )
            assertEquals(200, pendingResponse.statusCode)
            val pendingJson = mcpJson.parseToJsonElement(pendingResponse.body).jsonObject
            assertEquals(
                "pending",
                pendingJson["status"]?.jsonPrimitive?.content
            )

            // --- Step 7: Approve the request programmatically ---
            val approved = authorizationCodeManager.approve(displayCode)
            assertTrue(approved, "Approve should succeed")

            // --- Step 8: Poll status -> approved with auth code ---
            val approvedResponse = httpRequest(
                input,
                output,
                method = "GET",
                path = "/authorize/status?request_id=$requestId"
            )
            assertEquals(200, approvedResponse.statusCode)
            val approvedJson = mcpJson.parseToJsonElement(approvedResponse.body).jsonObject
            assertEquals(
                "approved",
                approvedJson["status"]?.jsonPrimitive?.content,
                "Status should be approved after approve()"
            )
            val authCode = approvedJson["code"]?.jsonPrimitive?.content
            assertNotNull(authCode, "Approved status should contain auth code")
            val returnedState = approvedJson["state"]?.jsonPrimitive?.content
            assertEquals(state, returnedState, "State should round-trip")

            // --- Step 9: Exchange code for token ---
            val tokenBody = "grant_type=authorization_code" +
                "&code=$authCode" +
                "&code_verifier=$codeVerifier"
            val tokenResponse = httpRequest(
                input,
                output,
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
            val accessToken = tokenJson["access_token"]?.jsonPrimitive?.content
            assertNotNull(accessToken, "Token response should contain access_token")
            assertEquals(
                "Bearer",
                tokenJson["token_type"]?.jsonPrimitive?.content
            )

            // --- Step 10: POST /mcp with Bearer token + initialize ---
            val initResponse = httpRequest(
                input,
                output,
                method = "POST",
                path = "/mcp",
                body = mcpJsonRpc("initialize", initializeParams()),
                bearerToken = accessToken
            )
            assertEquals(
                200,
                initResponse.statusCode,
                "Authenticated MCP initialize should succeed"
            )
            val initJson = mcpJson.parseToJsonElement(initResponse.body).jsonObject
            assertEquals("2.0", initJson["jsonrpc"]?.jsonPrimitive?.content)
            val initResult = initJson["result"]?.jsonObject
            assertNotNull(initResult, "Initialize should return result")
            assertTrue(
                initResult!!.containsKey("protocolVersion"),
                "Result should contain protocolVersion"
            )

            // Send initialized notification (required by MCP protocol)
            httpRequest(
                input,
                output,
                method = "POST",
                path = "/mcp",
                body = """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
                bearerToken = accessToken
            )

            // --- Step 11: tools/list ---
            val listResponse = httpRequest(
                input,
                output,
                method = "POST",
                path = "/mcp",
                body = mcpJsonRpc("tools/list", id = 2),
                bearerToken = accessToken
            )
            assertEquals(200, listResponse.statusCode)
            val listJson = mcpJson.parseToJsonElement(listResponse.body).jsonObject
            val tools = listJson["result"]?.jsonObject?.get("tools")?.jsonArray
            assertNotNull(tools, "tools/list should return tools array")
            assertTrue(tools!!.isNotEmpty(), "Should have at least one tool")
            assertEquals(
                "echo",
                tools[0].jsonObject["name"]?.jsonPrimitive?.content
            )

            // --- Step 12: tools/call echo ---
            val callResponse = httpRequest(
                input,
                output,
                method = "POST",
                path = "/mcp",
                body = mcpJsonRpc(
                    "tools/call",
                    """{"name":"echo","arguments":{"message":"hello through OAuth relay"}}""",
                    id = 3
                ),
                bearerToken = accessToken
            )
            assertEquals(200, callResponse.statusCode)
            val callJson = mcpJson.parseToJsonElement(callResponse.body).jsonObject
            val content = callJson["result"]?.jsonObject?.get("content")?.jsonArray
            assertNotNull(content, "tools/call should return content")
            assertEquals(
                "hello through OAuth relay",
                content!![0].jsonObject["text"]?.jsonPrimitive?.content
            )

            aiSocket.close()
            collectJob.cancel()
        } finally {
            tunnelClient.disconnect()
            coroutineContext.cancelChildren()
        }
    }

    // =========================================================================
    // MCP server provider
    // =========================================================================

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
                    request.params.arguments?.get("message")?.jsonPrimitive?.content ?: "empty"
                CallToolResult(content = listOf(TextContent(message)))
            }
        }
    }

    // =========================================================================
    // Device session handling
    // =========================================================================

    /**
     * Handles an incoming mux stream on the device side: TLS accept,
     * then bridge to a Ktor MCP server that shares our [authorizationCodeManager].
     */
    private suspend fun handleDeviceSession(
        stream: MuxStream,
        registry: InMemoryProviderRegistry,
        tokenStore: InMemoryTokenStore
    ) {
        val deviceSslContext = TestSslContexts.buildDeviceServer(deviceKeyStore)
        val certProvider = object : TlsCertProvider {
            override fun serverSslContext(): SSLContext = deviceSslContext
        }
        val factory = object : McpSessionFactory {
            override suspend fun create(): McpSessionHandle {
                val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)
                val server = embeddedServer(CIO, port = 0) {
                    configureMcpRouting(
                        registry = registry,
                        tokenStore = tokenStore,
                        deviceCodeManager = deviceCodeManager,
                        authorizationCodeManager = authorizationCodeManager,
                        hostname = INTEGRATION_HOST,
                        integration = INTEGRATION
                    )
                }
                server.start(wait = false)
                val port = server.engine.resolvedConnectors().first().port
                return McpSessionHandle(port = port, stop = { server.stop() })
            }
        }
        val handler = SessionHandler(certProvider = certProvider, mcpSessionFactory = factory)
        handler.handleStream(stream)
    }

    // =========================================================================
    // TunnelClient connection
    // =========================================================================

    private suspend fun connectTunnelClient(): TunnelClientImpl {
        val sslContext = TestSslContexts.buildMtls(deviceKeyStore, caCert)
        val wsFactory = MtlsWebSocketFactory(sslContext)
        val client = TunnelClientImpl(
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO),
            webSocketFactory = wsFactory
        )
        client.connect("wss://$RELAY_HOSTNAME:$relayPort/ws")
        assertEquals(
            com.rousecontext.tunnel.TunnelState.CONNECTED,
            client.state.value,
            "TunnelClient should be CONNECTED"
        )
        return client
    }

    // =========================================================================
    // AI client connection
    // =========================================================================

    private fun connectAiClient(): SSLSocket {
        val factory = TestSslContexts.buildAiClientSocketFactory(caCert, deviceCert)
        val socket = factory.createSocket(
            "127.0.0.1",
            relayPort
        ) as SSLSocket
        socket.soTimeout = 30_000
        val params = socket.sslParameters
        params.serverNames = listOf(
            javax.net.ssl.SNIHostName("$DEVICE_SUBDOMAIN.$RELAY_HOSTNAME")
        )
        socket.sslParameters = params
        socket.startHandshake()
        return socket
    }

    // =========================================================================
    // HTTP helpers
    // =========================================================================

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

    // =========================================================================
    // JSON-RPC helpers
    // =========================================================================

    private fun mcpJsonRpc(method: String, params: String? = null, id: Int = 1): String {
        val paramsStr = if (params != null) ""","params":$params""" else ""
        return """{"jsonrpc":"2.0","method":"$method"$paramsStr,"id":$id}"""
    }

    private fun initializeParams(): String =
        """{"protocolVersion":"2025-03-26","capabilities":{},""" +
            """"clientInfo":{"name":"oauth-e2e-test","version":"1.0"}}"""

    // =========================================================================
    // PKCE helpers
    // =========================================================================

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
