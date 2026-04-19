package com.rousecontext.app.integration

import com.rousecontext.app.integration.harness.AppIntegrationTestHarness
import com.rousecontext.app.integration.harness.TestEchoMcpIntegration
import com.rousecontext.app.token.TokenDatabase
import com.rousecontext.mcp.core.INTERNAL_TOKEN_HEADER
import com.rousecontext.mcp.core.McpSession
import com.rousecontext.mcp.core.TokenStore
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Socket
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Integration-tier coverage for the device-issued OAuth 2.1 authorization
 * code + device code flow that gates every AI client's access to an MCP
 * integration (issue #279, umbrella #270).
 *
 * Why this exists: if the OAuth dance breaks, no client can reach any
 * device-side tool. The pieces are each unit-tested (`AuthorizationCodeFlowTest`,
 * `AuthApprovalReceiverTest`, `RoomTokenStoreTest`, `HttpRoutingTest`, ...),
 * but the three-endpoint choreography ([/.well-known/...], [/register],
 * [/authorize] + HTML approval + [/authorize/status], [/token] polling,
 * `Authorization: Bearer <token>` reuse) has not been exercised
 * end-to-end through the production Koin graph until now.
 *
 * ## Why loopback TCP instead of SNI passthrough
 *
 * [McpSessionTestHarness] already documents why the synthetic AI client
 * hits `McpSession`'s loopback HTTP server directly. The OAuth endpoints
 * live on the same Ktor router as the `/mcp` endpoint those batch B
 * tests drive, so the same loopback driver pattern applies -- we skip
 * the relay + bridge + TLS legs (blocked for Robolectric by #262) and
 * keep the assertions focused on the *device-side* OAuth pipeline the
 * real production `RoomTokenStore` + `AuthorizationCodeManager` +
 * `configureMcpRouting` form when wired by Koin.
 *
 * ## Scenarios covered
 *
 *  - Happy path: DCR -> /authorize (PKCE) -> approve via real
 *    `authorizationCodeManager.approve(displayCode)` (the exact call
 *    site [com.rousecontext.app.receivers.AuthApprovalReceiver] uses)
 *    -> /token -> Bearer /mcp request succeeds. Token survives in the
 *    real Room-backed `RoomTokenStore`.
 *  - Token reuse: a second /mcp call with the same access token does
 *    not need to repeat the OAuth flow.
 *  - Refresh rotation (OAuth 2.1 §4.14): POST /token with
 *    `grant_type=refresh_token` yields a new pair, the old refresh
 *    token is rejected on replay, and the token family is revoked.
 *  - Token expiry mid-session: forcing `expiresAt = 0` on the access
 *    token row returns 401 on the next /mcp call; the client then
 *    performs a refresh and retries successfully -- the end-user never
 *    sees the 401.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OAuthDeviceFlowIntegrationTest {

    private val harness = AppIntegrationTestHarness(
        integrationsFactory = { listOf(TestEchoMcpIntegration()) }
    )

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        harness.start()
    }

    @After
    fun tearDown() {
        harness.stop()
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------
    // Happy path
    // ------------------------------------------------------------------

    @Suppress("LongMethod")
    @Test
    fun `full OAuth code flow issues token, protects mcp, persists in RoomTokenStore`() =
        runBlocking {
            harness.provisionDevice()
            harness.enableIntegrations(listOf(TestEchoMcpIntegration.ID))

            val mcpSession: McpSession = harness.koin.get()
            mcpSession.resolvePort()

            openClientSocket(mcpSession).use { client ->
                // Step 1: unauthenticated /mcp -> 401.
                val unauth = client.doJson(
                    method = "POST",
                    path = "/mcp",
                    body = initializeRpcBody()
                )
                assertEquals(
                    "pre-auth MCP call must be rejected with 401",
                    401,
                    unauth.statusCode
                )
                assertTrue(
                    "401 must include WWW-Authenticate with metadata URL",
                    unauth.headers["www-authenticate"]?.contains(
                        "oauth-protected-resource"
                    ) == true
                )

                // Step 2: discover authorization server metadata.
                val metadata = client.doJson(
                    method = "GET",
                    path = "/.well-known/oauth-authorization-server"
                )
                assertEquals(200, metadata.statusCode)
                val metadataJson = json.parseToJsonElement(metadata.body).jsonObject
                assertNotNull(metadataJson["authorization_endpoint"])
                assertNotNull(metadataJson["token_endpoint"])
                assertNotNull(metadataJson["registration_endpoint"])

                // Step 3: dynamic client registration.
                val register = client.doJson(
                    method = "POST",
                    path = "/register",
                    body = """
                        {"client_name":"oauth-integration-test",
                         "redirect_uris":["http://localhost:3000/cb"]}
                    """.trimIndent()
                )
                assertEquals(
                    "DCR should succeed, got body=${register.body}",
                    201,
                    register.statusCode
                )
                val registerJson = json.parseToJsonElement(register.body).jsonObject
                val clientId = registerJson["client_id"]?.jsonPrimitive?.content
                assertNotNull(clientId)

                // Step 4: /authorize with PKCE.
                val codeVerifier = generateCodeVerifier()
                val codeChallenge = generateCodeChallenge(codeVerifier)
                val state = "state-${System.nanoTime()}"
                val authorizePath = buildAuthorizeUrl(
                    clientId = clientId!!,
                    codeChallenge = codeChallenge,
                    state = state,
                    redirectUri = "http://localhost:3000/cb"
                )
                val authorize = client.doJson(method = "GET", path = authorizePath)
                assertEquals(200, authorize.statusCode)
                val displayCode = extractDisplayCode(authorize.body)

                // Step 5: user approval via the same call site
                // AuthApprovalReceiver uses in production.
                assertTrue(
                    "authorizationCodeManager.approve(displayCode) must accept " +
                        "the pending request; got false",
                    mcpSession.authorizationCodeManager.approve(displayCode)
                )

                // Step 6: poll /authorize/status -> approved + auth code.
                val requestId = extractRequestId(authorize.body)
                val status = client.doJson(
                    method = "GET",
                    path = "/authorize/status?request_id=$requestId"
                )
                assertEquals(200, status.statusCode)
                val statusJson = json.parseToJsonElement(status.body).jsonObject
                assertEquals(
                    "approved",
                    statusJson["status"]?.jsonPrimitive?.content
                )
                val authCode = statusJson["code"]!!.jsonPrimitive.content
                assertEquals(state, statusJson["state"]?.jsonPrimitive?.content)

                // Step 7: exchange code for tokens.
                val tokenResp = client.doForm(
                    method = "POST",
                    path = "/token",
                    params = mapOf(
                        "grant_type" to "authorization_code",
                        "code" to authCode,
                        "code_verifier" to codeVerifier
                    )
                )
                assertEquals(
                    "Token exchange must succeed; got: ${tokenResp.body}",
                    200,
                    tokenResp.statusCode
                )
                val tokenJson = json.parseToJsonElement(tokenResp.body).jsonObject
                val accessToken =
                    tokenJson["access_token"]!!.jsonPrimitive.content
                val refreshToken =
                    tokenJson["refresh_token"]!!.jsonPrimitive.content
                assertEquals(
                    "Bearer",
                    tokenJson["token_type"]?.jsonPrimitive?.content
                )

                // Step 8: use the access token on /mcp.
                val init = client.doJson(
                    method = "POST",
                    path = "/mcp",
                    body = initializeRpcBody(),
                    bearer = accessToken
                )
                assertEquals(
                    "Authenticated initialize should succeed; body=${init.body}",
                    200,
                    init.statusCode
                )
                val sessionId = init.headers["mcp-session-id"]
                assertNotNull("Ktor must set mcp-session-id on init", sessionId)
                client.doJson(
                    method = "POST",
                    path = "/mcp",
                    body = """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
                    bearer = accessToken,
                    sessionId = sessionId
                )

                val call = client.doJson(
                    method = "POST",
                    path = "/mcp",
                    body = """
                        {"jsonrpc":"2.0","id":2,"method":"tools/call",
                         "params":{"name":"echo",
                                   "arguments":{"message":"oauth-round-trip"}}}
                    """.trimIndent(),
                    bearer = accessToken,
                    sessionId = sessionId
                )
                assertEquals(200, call.statusCode)
                assertTrue(
                    "echo tool must round-trip the message: ${call.body}",
                    call.body.contains("oauth-round-trip")
                )

                // Step 9: token persisted in the real RoomTokenStore and reusable
                // without a second OAuth flow.
                val tokenStore: TokenStore = harness.koin.get()
                assertTrue(
                    "RoomTokenStore must validate the just-issued access token",
                    tokenStore.validateToken(TestEchoMcpIntegration.ID, accessToken)
                )
                assertEquals(
                    "Exactly one active token row should exist for this integration",
                    1,
                    tokenStore.listTokens(TestEchoMcpIntegration.ID).size
                )

                // Re-use: a fresh tools/call on the same session keeps working
                // with the original token. This is what the "don't re-auth per
                // request" expectation means at the wire level.
                val reuse = client.doJson(
                    method = "POST",
                    path = "/mcp",
                    body = """
                        {"jsonrpc":"2.0","id":3,"method":"tools/call",
                         "params":{"name":"echo",
                                   "arguments":{"message":"reuse-call"}}}
                    """.trimIndent(),
                    bearer = accessToken,
                    sessionId = sessionId
                )
                assertEquals(200, reuse.statusCode)
                assertTrue(reuse.body.contains("reuse-call"))

                // Assertion: still exactly one active token row -- the reuse
                // path must not have minted another pair (no second OAuth flow).
                assertEquals(
                    "Reusing the access token must not create a second token row",
                    1,
                    tokenStore.listTokens(TestEchoMcpIntegration.ID).size
                )
            }
        }

    // ------------------------------------------------------------------
    // Refresh rotation + reuse detection (OAuth 2.1 §4.14)
    // ------------------------------------------------------------------

    @Suppress("LongMethod")
    @Test
    fun `refresh_token issues new pair and old refresh cannot be replayed`() = runBlocking {
        harness.provisionDevice()
        harness.enableIntegrations(listOf(TestEchoMcpIntegration.ID))

        val mcpSession: McpSession = harness.koin.get()
        mcpSession.resolvePort()

        openClientSocket(mcpSession).use { client ->
            val initial = runOAuthFlow(client, mcpSession)

            // Step A: refresh -> new access + new refresh token, old access invalid.
            val refresh = client.doForm(
                method = "POST",
                path = "/token",
                params = mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to initial.refreshToken
                )
            )
            assertEquals(
                "Refresh must succeed; got: ${refresh.body}",
                200,
                refresh.statusCode
            )
            val refreshJson = json.parseToJsonElement(refresh.body).jsonObject
            val newAccess = refreshJson["access_token"]!!.jsonPrimitive.content
            val newRefresh = refreshJson["refresh_token"]!!.jsonPrimitive.content
            assertFalse(
                "Rotated access token must differ from the old one",
                newAccess == initial.accessToken
            )
            assertFalse(
                "Rotated refresh token must differ from the old one",
                newRefresh == initial.refreshToken
            )

            val tokenStore: TokenStore = harness.koin.get()
            assertTrue(
                "Newly rotated access token must validate",
                tokenStore.validateToken(TestEchoMcpIntegration.ID, newAccess)
            )
            assertFalse(
                "Old access token must NOT validate after rotation",
                tokenStore.validateToken(TestEchoMcpIntegration.ID, initial.accessToken)
            )

            // Step B: replay the *old* refresh token -> rejected, and the
            // token family must be torn down (issue #207 family).
            val replay = client.doForm(
                method = "POST",
                path = "/token",
                params = mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to initial.refreshToken
                )
            )
            assertEquals(
                "Replay of a rotated refresh token must be rejected with 400",
                400,
                replay.statusCode
            )
            assertTrue(
                "Replay response must include invalid_grant: ${replay.body}",
                replay.body.contains("invalid_grant")
            )
            assertFalse(
                "Reuse detection must revoke the rotated access token too",
                tokenStore.validateToken(TestEchoMcpIntegration.ID, newAccess)
            )
            assertEquals(
                "Entire token family must be wiped after reuse detection",
                0,
                tokenStore.listTokens(TestEchoMcpIntegration.ID).size
            )
        }
    }

    // ------------------------------------------------------------------
    // Token expiry mid-session
    // ------------------------------------------------------------------

    @Suppress("LongMethod")
    @Test
    fun `expired access token is refreshed, retry succeeds transparently`() = runBlocking {
        harness.provisionDevice()
        harness.enableIntegrations(listOf(TestEchoMcpIntegration.ID))

        val mcpSession: McpSession = harness.koin.get()
        mcpSession.resolvePort()

        openClientSocket(mcpSession).use { client ->
            val tokens = runOAuthFlow(client, mcpSession)

            // Sanity: /mcp works with fresh token.
            val init = client.doJson(
                method = "POST",
                path = "/mcp",
                body = initializeRpcBody(),
                bearer = tokens.accessToken
            )
            assertEquals(200, init.statusCode)

            // Force the access token row to be expired via a direct DB write.
            // RoomTokenStore's validateToken path checks
            // `now > entity.expiresAt`, so setting expiresAt to 0 makes
            // the next /mcp call receive a 401 as if wall-clock time had
            // advanced past the hour-long access token TTL.
            expireAllAccessTokensInRoom()

            val expired = client.doJson(
                method = "POST",
                path = "/mcp",
                body = """{"jsonrpc":"2.0","id":42,"method":"tools/list"}""",
                bearer = tokens.accessToken,
                sessionId = init.headers["mcp-session-id"]
            )
            assertEquals(
                "Expired access token must be rejected with 401",
                401,
                expired.statusCode
            )

            // Client-side refresh: trade the still-valid refresh token for
            // a new pair, then retry the original request. This is what a
            // compliant MCP client does in response to 401.
            val refresh = client.doForm(
                method = "POST",
                path = "/token",
                params = mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to tokens.refreshToken
                )
            )
            assertEquals(
                "Refresh after 401 must succeed; body=${refresh.body}",
                200,
                refresh.statusCode
            )
            val refreshed =
                json.parseToJsonElement(refresh.body).jsonObject
            val freshAccess = refreshed["access_token"]!!.jsonPrimitive.content

            // Must re-initialise: the prior session entry is still bound to
            // the same clientId (so the MCP server accepts resuming it) but
            // the natural pattern an MCP client follows after a 401 is to
            // re-run initialize under the new token before replaying the
            // failed request. Exercise that shape here.
            val reinit = client.doJson(
                method = "POST",
                path = "/mcp",
                body = initializeRpcBody(id = 100),
                bearer = freshAccess
            )
            assertEquals(200, reinit.statusCode)
            val newSessionId = reinit.headers["mcp-session-id"]!!
            client.doJson(
                method = "POST",
                path = "/mcp",
                body = """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
                bearer = freshAccess,
                sessionId = newSessionId
            )
            val retry = client.doJson(
                method = "POST",
                path = "/mcp",
                body = """{"jsonrpc":"2.0","id":101,"method":"tools/list"}""",
                bearer = freshAccess,
                sessionId = newSessionId
            )
            assertEquals(
                "Retry under the refreshed access token must succeed",
                200,
                retry.statusCode
            )
            assertTrue(
                "tools/list must return the echo tool after transparent refresh",
                retry.body.contains("echo")
            )

            // The end-user-visible contract: the 401 was recoverable via
            // a pure token refresh (no user re-approval), and the replay
            // path did not trigger reuse detection.
            val tokenStore: TokenStore = harness.koin.get()
            assertEquals(
                "Refresh-after-expiry must leave exactly one active token row",
                1,
                tokenStore.listTokens(TestEchoMcpIntegration.ID).size
            )
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Runs the DCR + authorize + approve + token steps to arrive at a
     * valid `TokenPair` the way the happy-path test does, without
     * repeating all the assertions. Returned tokens are the live
     * production strings understood by `RoomTokenStore`.
     */
    private fun runOAuthFlow(client: ClientSocket, mcpSession: McpSession): TokenPair {
        val register = client.doJson(
            method = "POST",
            path = "/register",
            body = """
                {"client_name":"oauth-integration-test",
                 "redirect_uris":["http://localhost:3000/cb"]}
            """.trimIndent()
        )
        check(register.statusCode == 201) {
            "DCR failed: ${register.statusCode} ${register.body}"
        }
        val registerJson = json.parseToJsonElement(register.body).jsonObject
        val clientId = registerJson["client_id"]!!.jsonPrimitive.content

        val codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)
        val state = "s-${System.nanoTime()}"
        val authorize = client.doJson(
            method = "GET",
            path = buildAuthorizeUrl(
                clientId = clientId,
                codeChallenge = codeChallenge,
                state = state,
                redirectUri = "http://localhost:3000/cb"
            )
        )
        check(authorize.statusCode == 200) { "authorize failed: ${authorize.statusCode}" }
        val displayCode = extractDisplayCode(authorize.body)
        val requestId = extractRequestId(authorize.body)
        check(mcpSession.authorizationCodeManager.approve(displayCode))

        val statusResp = client.doJson(
            method = "GET",
            path = "/authorize/status?request_id=$requestId"
        )
        val statusJson = json.parseToJsonElement(statusResp.body).jsonObject
        val authCode = statusJson["code"]!!.jsonPrimitive.content

        val tokenResp = client.doForm(
            method = "POST",
            path = "/token",
            params = mapOf(
                "grant_type" to "authorization_code",
                "code" to authCode,
                "code_verifier" to codeVerifier
            )
        )
        check(tokenResp.statusCode == 200) {
            "token exchange failed: ${tokenResp.statusCode} ${tokenResp.body}"
        }
        val t = json.parseToJsonElement(tokenResp.body).jsonObject
        return TokenPair(
            accessToken = t["access_token"]!!.jsonPrimitive.content,
            refreshToken = t["refresh_token"]!!.jsonPrimitive.content
        )
    }

    /**
     * Reaches through the live Room database to mark every access-token
     * row as expired. This simulates the wall-clock "more than an hour
     * has passed since issuance" condition without requiring a virtual
     * clock in `RoomTokenStore` (which reads `System.currentTimeMillis()`
     * directly). The refresh-token row is deliberately left untouched
     * so the client's subsequent refresh succeeds -- the point of the
     * mid-session-expiry test.
     */
    private fun expireAllAccessTokensInRoom() {
        val db: TokenDatabase = harness.koin.get()
        db.openHelper.writableDatabase.execSQL("UPDATE tokens SET expiresAt = 0")
    }

    private fun buildAuthorizeUrl(
        clientId: String,
        codeChallenge: String,
        state: String,
        redirectUri: String
    ): String = "/authorize" +
        "?response_type=code" +
        "&client_id=$clientId" +
        "&code_challenge=$codeChallenge" +
        "&code_challenge_method=S256" +
        "&redirect_uri=${URLEncoder.encode(redirectUri, "UTF-8")}" +
        "&state=$state"

    private fun extractDisplayCode(html: String): String {
        // Auth page renders the display code as `<div class="code">XXXXXX-XXXXXX</div>`.
        val match = Regex("""class="code">([A-Z2-9]{6}-[A-Z2-9]{6})<""").find(html)
            ?: error("display code not found in /authorize HTML")
        return match.groupValues[1]
    }

    private fun extractRequestId(html: String): String {
        val match = Regex("request_id=([a-f0-9-]+)").find(html)
            ?: error("request_id not found in /authorize HTML")
        return match.groupValues[1]
    }

    private fun initializeRpcBody(id: Int = 1): String =
        """{"jsonrpc":"2.0","id":$id,"method":"initialize","params":""" +
            """{"protocolVersion":"2025-03-26","capabilities":{},""" +
            """"clientInfo":{"name":"oauth-it","version":"1.0"}}}"""

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

    private fun openClientSocket(session: McpSession): ClientSocket = ClientSocket(
        hostHeader = "synth-${TestEchoMcpIntegration.ID}.example.test",
        session = session
    )

    private data class TokenPair(val accessToken: String, val refreshToken: String)

    /**
     * Lightweight HTTP/1.1 client over a single keep-alive socket to the
     * McpSession's loopback port. Mirrors [McpTestDriver] but exposes
     * both JSON and form-encoded bodies and the raw OAuth endpoints; the
     * existing driver is tool-call-only.
     */
    private inner class ClientSocket(
        private val hostHeader: String,
        private val session: McpSession
    ) : AutoCloseable {
        private val socket = Socket("127.0.0.1", session.port).also {
            it.soTimeout = SOCKET_TIMEOUT_MS
        }
        private val input: InputStream = socket.inputStream
        private val output: OutputStream = socket.outputStream

        fun doJson(
            method: String,
            path: String,
            body: String? = null,
            bearer: String? = null,
            sessionId: String? = null
        ): HttpResponse = doRequest(
            method = method,
            path = path,
            body = body,
            contentType = "application/json",
            bearer = bearer,
            sessionId = sessionId
        )

        fun doForm(method: String, path: String, params: Map<String, String>): HttpResponse {
            val encoded = params.entries.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }
            return doRequest(
                method = method,
                path = path,
                body = encoded,
                contentType = "application/x-www-form-urlencoded",
                bearer = null,
                sessionId = null
            )
        }

        @Suppress("LongParameterList")
        private fun doRequest(
            method: String,
            path: String,
            body: String?,
            contentType: String,
            bearer: String?,
            sessionId: String?
        ): HttpResponse {
            val sb = StringBuilder()
            sb.append("$method $path HTTP/1.1\r\n")
            sb.append("Host: $hostHeader\r\n")
            sb.append("Connection: keep-alive\r\n")
            // Ktor's InternalTokenGuard gates EVERY endpoint, including the
            // well-known + OAuth routes. The synthetic AI client here is
            // "the bridge" from the production perspective, so it must
            // present the same rotating per-session token the bridge does.
            sb.append("$INTERNAL_TOKEN_HEADER: ${session.internalToken}\r\n")
            if (bearer != null) sb.append("Authorization: Bearer $bearer\r\n")
            if (sessionId != null) sb.append("Mcp-Session-Id: $sessionId\r\n")
            val bodyBytes = body?.toByteArray(Charsets.UTF_8)
            if (bodyBytes != null) {
                sb.append("Content-Type: $contentType\r\n")
                sb.append("Content-Length: ${bodyBytes.size}\r\n")
            }
            sb.append("\r\n")
            output.write(sb.toString().toByteArray(Charsets.UTF_8))
            if (bodyBytes != null) output.write(bodyBytes)
            output.flush()

            return readResponse(input)
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }

    private data class HttpResponse(
        val statusCode: Int,
        val headers: Map<String, String>,
        val body: String
    )

    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements", "NestedBlockDepth")
    private fun readResponse(input: InputStream): HttpResponse {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        val statusLine = reader.readLine() ?: error("no status line from server")
        require(statusLine.startsWith("HTTP/1.1")) { "bad status line: $statusLine" }
        val statusCode = statusLine.split(" ")[1].toInt()

        val headers = mutableMapOf<String, String>()
        var contentLength = -1
        var chunked = false
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                headers[line.substring(0, colonIdx).lowercase()] =
                    line.substring(colonIdx + 1).trim()
            }
            val lc = line.lowercase()
            if (lc.startsWith("content-length:")) {
                contentLength = lc.substringAfter(":").trim().toInt()
            }
            if (lc.startsWith("transfer-encoding:") && lc.contains("chunked")) {
                chunked = true
            }
        }

        val body = when {
            contentLength > 0 -> readFixedLength(reader, contentLength)
            chunked -> readChunked(reader)
            else -> ""
        }
        return HttpResponse(statusCode, headers, body)
    }

    private fun readFixedLength(reader: BufferedReader, length: Int): String {
        val buf = CharArray(length)
        var read = 0
        while (read < length) {
            val n = reader.read(buf, read, length - read)
            if (n == -1) break
            read += n
        }
        return String(buf, 0, read)
    }

    @Suppress("LoopWithTooManyJumpStatements")
    private fun readChunked(reader: BufferedReader): String {
        val sb = StringBuilder()
        while (true) {
            val sizeLine = reader.readLine() ?: break
            val size = sizeLine.trim().toInt(16)
            if (size == 0) {
                reader.readLine()
                break
            }
            sb.append(readFixedLength(reader, size))
            reader.readLine()
        }
        return sb.toString()
    }

    private companion object {
        private const val SOCKET_TIMEOUT_MS = 20_000
    }
}
