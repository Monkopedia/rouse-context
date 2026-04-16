package com.rousecontext.e2e

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * End-to-end MCP test that connects through the real relay to a real device.
 *
 * Requires:
 * - A debug build installed on a device with test integration available
 * - Device connected via adb through adolin.lan
 * - The relay running at relay.rousecontext.com
 *
 * Run with:
 * ```
 * ./gradlew :e2e:e2eTest \
 *   -Dmcp.url=https://foo-test.wet-scone.rousecontext.com/mcp \
 *   -Dadb.host=adolin.lan
 * ```
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class McpEndToEndTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private lateinit var mcpUrl: String
    private lateinit var baseUrl: String
    private lateinit var adbHost: String
    private lateinit var integrationId: String

    // State accumulated across ordered tests
    private var authServerUrl: String? = null
    private var clientId: String? = null
    private var clientSecret: String? = null
    private var codeVerifier: String? = null
    private var codeChallenge: String? = null
    private var authCode: String? = null
    private var accessToken: String? = null
    private var refreshToken: String? = null

    @BeforeAll
    fun setup() {
        adbHost = System.getProperty("adb.host", "adolin.lan")
        integrationId = System.getProperty("mcp.integration", "test")

        // Ensure app is running and Koin is initialized
        adb(
            "shell",
            "am",
            "start",
            "-n",
            "com.rousecontext.debug/com.rousecontext.app.MainActivity"
        )
        Thread.sleep(3000)

        // Enable the integration
        enableIntegration(integrationId)
        Thread.sleep(1000)

        // Read device URL from adb — auto-discover subdomain + integration secret
        val explicitUrl = System.getProperty("mcp.url", "")
        if (explicitUrl.isNotEmpty()) {
            mcpUrl = explicitUrl
        } else {
            mcpUrl = discoverMcpUrl(integrationId)
        }
        baseUrl = mcpUrl.removeSuffix("/mcp")
        println("MCP URL: $mcpUrl")

        // Generate PKCE code verifier and challenge
        val random = SecureRandom()
        val verifierBytes = ByteArray(32)
        random.nextBytes(verifierBytes)
        codeVerifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier!!.toByteArray())
        codeChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun discoverMcpUrl(integration: String): String {
        val subdomain = adb(
            "shell",
            "run-as",
            "com.rousecontext.debug",
            "cat",
            "files/rouse_subdomain.txt"
        ).trim()
        require(subdomain.isNotEmpty()) { "No subdomain found on device" }

        val secretsJson = adb(
            "shell",
            "run-as",
            "com.rousecontext.debug",
            "cat",
            "files/rouse_integration_secrets.json"
        ).trim()
        require(secretsJson.isNotEmpty()) { "No integration secrets found on device" }

        val secrets = json.parseToJsonElement(secretsJson).jsonObject
        val secret = secrets[integration]?.jsonPrimitive?.content
            ?: error("No secret for integration '$integration'. Available: ${secrets.keys}")

        return "https://$secret.$subdomain.rousecontext.com/mcp"
    }

    @Test
    @Order(0)
    fun `00 - unauthenticated POST returns 401 with WWW-Authenticate`() {
        val body = buildJsonObject {
            put("method", "initialize")
            put("jsonrpc", "2.0")
            put("id", 1)
        }

        val request = Request.Builder()
            .url(mcpUrl)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals(401, response.code, "Expected 401 for unauthenticated request")
            val wwwAuth = response.header("WWW-Authenticate")
            assertNotNull(wwwAuth, "Missing WWW-Authenticate header on 401 response")
            assertTrue(
                wwwAuth!!.contains("resource_metadata"),
                "WWW-Authenticate should contain 'resource_metadata', got: $wwwAuth"
            )
            assertTrue(
                wwwAuth.contains("/.well-known/oauth-protected-resource"),
                "WWW-Authenticate should reference " +
                    "'/.well-known/oauth-protected-resource', got: $wwwAuth"
            )
            println("401 WWW-Authenticate: $wwwAuth")
        }
    }

    @Test
    @Order(1)
    fun `01 - discover OAuth protected resource`() {
        val request = Request.Builder()
            .url("$baseUrl/.well-known/oauth-protected-resource")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val bodyText = response.body?.string() ?: ""
            assertEquals(200, response.code, "Protected resource discovery failed: $bodyText")
            val body = json.parseToJsonElement(bodyText).jsonObject
            val authServers = body["authorization_servers"]?.jsonArray
            assertNotNull(authServers, "Missing authorization_servers")
            assertTrue(authServers!!.isNotEmpty(), "Empty authorization_servers")
            authServerUrl = authServers[0].jsonPrimitive.content
            println("Auth server: $authServerUrl")
        }
    }

    @Test
    @Order(2)
    fun `02 - discover OAuth authorization server metadata`() {
        val request = Request.Builder()
            .url("$authServerUrl/.well-known/oauth-authorization-server")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val bodyText = response.body?.string() ?: ""
            assertEquals(200, response.code, "Auth server metadata failed: $bodyText")
            val body = json.parseToJsonElement(bodyText).jsonObject
            assertNotNull(body["authorization_endpoint"], "Missing authorization_endpoint")
            assertNotNull(body["token_endpoint"], "Missing token_endpoint")
            assertNotNull(body["registration_endpoint"], "Missing registration_endpoint")
            println(
                "Endpoints discovered: " +
                    "auth=${body["authorization_endpoint"]}, " +
                    "token=${body["token_endpoint"]}"
            )
        }
    }

    @Test
    @Order(3)
    fun `03 - register client`() {
        val registerBody = buildJsonObject {
            put(
                "redirect_uris",
                kotlinx.serialization.json.JsonArray(
                    listOf(
                        kotlinx.serialization.json.JsonPrimitive("http://localhost:9999/callback")
                    )
                )
            )
            put("client_name", "E2E Test Client")
            put("token_endpoint_auth_method", "client_secret_post")
            put(
                "grant_types",
                kotlinx.serialization.json.JsonArray(
                    listOf(
                        kotlinx.serialization.json.JsonPrimitive("authorization_code"),
                        kotlinx.serialization.json.JsonPrimitive("refresh_token")
                    )
                )
            )
            put(
                "response_types",
                kotlinx.serialization.json.JsonArray(
                    listOf(kotlinx.serialization.json.JsonPrimitive("code"))
                )
            )
        }

        val request = Request.Builder()
            .url("$baseUrl/register")
            .post(registerBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val bodyText = response.body?.string() ?: ""
            assertEquals(201, response.code, "Client registration failed: $bodyText")
            val body = json.parseToJsonElement(bodyText).jsonObject
            clientId = body["client_id"]?.jsonPrimitive?.content
            clientSecret = body["client_secret"]?.jsonPrimitive?.content
            assertNotNull(clientId, "Missing client_id")
            println("Registered client: $clientId")
        }
    }

    @Test
    @Order(4)
    fun `04 - authorize and approve via adb`() {
        // Start authorization — get the HTML page with display code
        val authUrl = "$baseUrl/authorize?" +
            "response_type=code&" +
            "client_id=$clientId&" +
            "redirect_uri=${
                java.net.URLEncoder.encode("http://localhost:9999/callback", "UTF-8")
            }&" +
            "code_challenge=$codeChallenge&" +
            "code_challenge_method=S256&" +
            "state=e2etest"

        val request = Request.Builder().url(authUrl).get().build()

        client.newCall(request).execute().use { response ->
            val html = response.body?.string() ?: ""
            assertEquals(200, response.code, "Authorization page failed: $html")

            // Extract display code from HTML — format is 6+6 chars with dash (e.g. NUVHNK-Z2V638)
            val codePattern = Regex("""([A-Z0-9]{6}-[A-Z0-9]{6})""")
            val match = codePattern.find(html)
            assertNotNull(match, "Could not find display code in authorize page")
            val displayCode = match!!.value
            println("Display code: $displayCode")

            // Approve via adb broadcast
            approveAuth(displayCode)

            // Poll for approval
            val requestIdPattern = Regex("""request_id=([a-f0-9-]+)""")
            val requestIdMatch = requestIdPattern.find(html)
            assertNotNull(requestIdMatch, "Could not find request_id in authorize page")
            val requestId = requestIdMatch!!.groupValues[1]

            // Poll status until approved
            var approved = false
            for (i in 1..10) {
                Thread.sleep(1000)
                val statusRequest = Request.Builder()
                    .url("$baseUrl/authorize/status?request_id=$requestId")
                    .get()
                    .build()

                client.newCall(statusRequest).execute().use { statusResponse ->
                    val statusBody = json.parseToJsonElement(
                        statusResponse.body!!.string()
                    ).jsonObject
                    val status = statusBody["status"]?.jsonPrimitive?.content
                    println("Poll $i: status=$status")
                    if (status == "approved") {
                        authCode = statusBody["code"]?.jsonPrimitive?.content
                        approved = true
                    }
                }
                if (approved) break
            }
            assertTrue(approved, "Authorization was not approved within 10 seconds")
            assertNotNull(authCode, "Missing authorization code")
            println("Auth code: $authCode")
        }
    }

    @Test
    @Order(5)
    fun `05 - exchange code for token`() {
        val formBody = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", authCode!!)
            .add("client_id", clientId!!)
            .add("code_verifier", codeVerifier!!)
            .add("redirect_uri", "http://localhost:9999/callback")
            .apply { clientSecret?.let { add("client_secret", it) } }
            .build()

        val request = Request.Builder()
            .url("$baseUrl/token")
            .post(formBody)
            .build()

        client.newCall(request).execute().use { response ->
            val bodyText = response.body?.string() ?: ""
            assertEquals(200, response.code, "Token exchange failed: $bodyText")
            val body = json.parseToJsonElement(bodyText).jsonObject
            accessToken = body["access_token"]?.jsonPrimitive?.content
            refreshToken = body["refresh_token"]?.jsonPrimitive?.content
            assertNotNull(accessToken, "Missing access_token")
            assertNotNull(refreshToken, "Missing refresh_token")
            println("Access token obtained: ${accessToken!!.take(10)}...")
            println("Refresh token obtained: ${refreshToken!!.take(10)}...")
        }
    }

    @Test
    @Order(6)
    fun `06 - MCP initialize`() {
        val initBody = buildJsonObject {
            put("method", "initialize")
            put("jsonrpc", "2.0")
            put("id", 1)
            put(
                "params",
                buildJsonObject {
                    put("protocolVersion", "2025-11-25")
                    put("capabilities", buildJsonObject {})
                    put(
                        "clientInfo",
                        buildJsonObject {
                            put("name", "E2E Test")
                            put("version", "1.0.0")
                        }
                    )
                }
            )
        }

        val request = Request.Builder()
            .url(mcpUrl)
            .post(initBody.toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $accessToken")
            .build()

        client.newCall(request).execute().use { response ->
            val bodyText = response.body?.string() ?: ""
            assertEquals(200, response.code, "MCP initialize failed: $bodyText")
            val body = json.parseToJsonElement(bodyText).jsonObject
            // Before #181 was fixed, a second initialize against a cached
            // per-integration Server produced an error response (HTTP 200 with
            // {jsonrpc, id, error} and no `result`). Surface that explicitly.
            val errorObj = body["error"]?.jsonObject
            assertNull(
                errorObj,
                "initialize returned JSON-RPC error instead of result: $bodyText"
            )
            val result = body["result"]?.jsonObject
            assertNotNull(result, "Missing result in initialize response; body=$bodyText")
            val serverInfo = result!!["serverInfo"]?.jsonObject
            assertNotNull(serverInfo, "Missing serverInfo; body=$bodyText")
            println(
                "Server: ${serverInfo!!["name"]?.jsonPrimitive?.content} " +
                    serverInfo["version"]?.jsonPrimitive?.content
            )
        }
    }

    @Test
    @Order(7)
    fun `07 - MCP tools list`() {
        val listBody = buildJsonObject {
            put("method", "tools/list")
            put("jsonrpc", "2.0")
            put("id", 2)
        }

        val request = Request.Builder()
            .url(mcpUrl)
            .post(listBody.toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $accessToken")
            .build()

        client.newCall(request).execute().use { response ->
            val bodyText = response.body?.string() ?: ""
            assertEquals(200, response.code, "tools/list failed: $bodyText")
            val body = json.parseToJsonElement(bodyText).jsonObject
            val tools = body["result"]?.jsonObject?.get("tools")?.jsonArray
            assertNotNull(tools, "Missing tools in response")
            assertTrue(tools!!.isNotEmpty(), "No tools returned")
            val toolNames = tools.map { it.jsonObject["name"]?.jsonPrimitive?.content }
            println("Tools: $toolNames")
            assertTrue(toolNames.contains("echo"), "Missing 'echo' tool")
        }
    }

    @Test
    @Order(8)
    fun `08 - MCP call echo tool`() {
        val callBody = buildJsonObject {
            put("method", "tools/call")
            put("jsonrpc", "2.0")
            put("id", 3)
            put(
                "params",
                buildJsonObject {
                    put("name", "echo")
                    put(
                        "arguments",
                        buildJsonObject {
                            put("message", "hello from e2e test")
                        }
                    )
                }
            )
        }

        val request = Request.Builder()
            .url(mcpUrl)
            .post(callBody.toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $accessToken")
            .build()

        client.newCall(request).execute().use { response ->
            val bodyText = response.body?.string() ?: ""
            assertEquals(200, response.code, "tools/call failed: $bodyText")
            val body = json.parseToJsonElement(bodyText).jsonObject
            val content = body["result"]?.jsonObject?.get("content")?.jsonArray
            assertNotNull(content, "Missing content in response")
            val text = content!![0].jsonObject["text"]?.jsonPrimitive?.content
            assertEquals("hello from e2e test", text, "Echo tool returned wrong content")
            println("Echo response: $text")
        }
    }

    @Test
    @Order(9)
    fun `09 - refresh token returns new access token`() {
        val oldAccessToken = accessToken!!

        val formBody = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken!!)
            .add("client_id", clientId!!)
            .apply { clientSecret?.let { add("client_secret", it) } }
            .build()

        val request = Request.Builder()
            .url("$baseUrl/token")
            .post(formBody)
            .build()

        client.newCall(request).execute().use { response ->
            val bodyText = response.body?.string() ?: ""
            assertEquals(200, response.code, "Refresh token exchange failed: $bodyText")
            val body = json.parseToJsonElement(bodyText).jsonObject
            val newAccessToken = body["access_token"]?.jsonPrimitive?.content
            val newRefreshToken = body["refresh_token"]?.jsonPrimitive?.content
            assertNotNull(newAccessToken, "Missing access_token in refresh response")
            assertNotNull(newRefreshToken, "Missing refresh_token in refresh response")
            assertTrue(
                newAccessToken != oldAccessToken,
                "Refresh should return a different access token"
            )
            accessToken = newAccessToken
            refreshToken = newRefreshToken
            println("New access token: ${newAccessToken!!.take(10)}...")
            println("New refresh token: ${newRefreshToken!!.take(10)}...")
        }
    }

    @Test
    @Order(10)
    fun `10 - new access token works for MCP calls`() {
        val callBody = buildJsonObject {
            put("method", "tools/call")
            put("jsonrpc", "2.0")
            put("id", 4)
            put(
                "params",
                buildJsonObject {
                    put("name", "echo")
                    put(
                        "arguments",
                        buildJsonObject {
                            put("message", "hello after refresh")
                        }
                    )
                }
            )
        }

        val request = Request.Builder()
            .url(mcpUrl)
            .post(callBody.toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $accessToken")
            .build()

        client.newCall(request).execute().use { response ->
            val bodyText = response.body?.string() ?: ""
            assertEquals(200, response.code, "MCP call with new access token failed: $bodyText")
            val body = json.parseToJsonElement(bodyText).jsonObject
            val text = body["result"]?.jsonObject
                ?.get("content")?.jsonArray
                ?.get(0)?.jsonObject
                ?.get("text")?.jsonPrimitive?.content
            assertEquals("hello after refresh", text, "Echo tool returned wrong content")
            println("Echo with refreshed token: $text")
        }
    }

    @Test
    @Order(11)
    fun `11 - old access token is revoked after refresh`() {
        // We saved oldAccessToken in step 09 via the accessToken field before overwrite,
        // but we need to use a token we know is old. Re-derive from the flow:
        // After step 09, accessToken was updated to the new one. The old one was the
        // value before the refresh. We need to track it separately.
        // Actually, we don't have the old token saved — let's do a second refresh
        // and verify the previous token stops working.

        // Current accessToken is from the refresh in step 09.
        val tokenBeforeRefresh = accessToken!!

        // Do another refresh to get yet another new token
        val formBody = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken!!)
            .add("client_id", clientId!!)
            .apply { clientSecret?.let { add("client_secret", it) } }
            .build()

        val request = Request.Builder()
            .url("$baseUrl/token")
            .post(formBody)
            .build()

        client.newCall(request).execute().use { response ->
            val bodyText = response.body?.string() ?: ""
            assertEquals(200, response.code, "Second refresh failed: $bodyText")
            val body = json.parseToJsonElement(bodyText).jsonObject
            accessToken = body["access_token"]?.jsonPrimitive?.content
            refreshToken = body["refresh_token"]?.jsonPrimitive?.content
            assertNotNull(accessToken, "Missing access_token")
            assertNotNull(refreshToken, "Missing refresh_token")
        }

        // Now try using the old access token — should be revoked
        val callBody = buildJsonObject {
            put("method", "tools/call")
            put("jsonrpc", "2.0")
            put("id", 5)
            put(
                "params",
                buildJsonObject {
                    put("name", "echo")
                    put(
                        "arguments",
                        buildJsonObject {
                            put("message", "should fail")
                        }
                    )
                }
            )
        }

        val oldRequest = Request.Builder()
            .url(mcpUrl)
            .post(callBody.toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $tokenBeforeRefresh")
            .build()

        client.newCall(oldRequest).execute().use { response ->
            assertEquals(
                401,
                response.code,
                "Old access token should be revoked after refresh, but got ${response.code}"
            )
            println("Old access token correctly rejected with 401")
        }
    }

    @Test
    @Order(12)
    fun `12 - used refresh token cannot be reused`() {
        // The refresh token from step 09 was already used in step 11.
        // We need to test with a known-consumed refresh token.
        // The current refreshToken is from step 11's refresh. Let's use it,
        // then try to use it again.
        val currentRefresh = refreshToken!!

        // First use — should succeed
        val formBody = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", currentRefresh)
            .add("client_id", clientId!!)
            .apply { clientSecret?.let { add("client_secret", it) } }
            .build()

        val request = Request.Builder()
            .url("$baseUrl/token")
            .post(formBody)
            .build()

        client.newCall(request).execute().use { response ->
            val bodyText = response.body?.string() ?: ""
            assertEquals(200, response.code, "First refresh use failed: $bodyText")
            val body = json.parseToJsonElement(bodyText).jsonObject
            accessToken = body["access_token"]?.jsonPrimitive?.content
            refreshToken = body["refresh_token"]?.jsonPrimitive?.content
        }

        // Second use of same refresh token — should fail (rotation)
        val replayBody = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", currentRefresh)
            .add("client_id", clientId!!)
            .apply { clientSecret?.let { add("client_secret", it) } }
            .build()

        val replayRequest = Request.Builder()
            .url("$baseUrl/token")
            .post(replayBody)
            .build()

        client.newCall(replayRequest).execute().use { response ->
            assertEquals(
                400,
                response.code,
                "Reused refresh token should be rejected, but got ${response.code}"
            )
            val bodyText = response.body?.string() ?: ""
            val body = json.parseToJsonElement(bodyText).jsonObject
            val error = body["error"]?.jsonPrimitive?.content
            assertEquals("invalid_grant", error, "Expected invalid_grant error")
            println("Reused refresh token correctly rejected: $error")
        }
    }

    // --- ADB helpers ---

    private fun adb(vararg args: String): String {
        val serial = System.getProperty("adb.serial", "")
        val remoteAdb = if (serial.isNotEmpty()) {
            "ANDROID_SERIAL=$serial /opt/android-sdk/platform-tools/adb"
        } else {
            "/opt/android-sdk/platform-tools/adb"
        }
        // Pass as a single shell-quoted string so the env var applies.
        val cmd = listOf("ssh", adbHost, remoteAdb) + args.toList()
        val process = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(15, TimeUnit.SECONDS)
        return output.trim()
    }

    private fun enableIntegration(id: String) {
        val result = adb(
            "shell", "content", "call",
            "--uri", "content://com.rousecontext.debug.test",
            "--method", "enable_integration",
            "--extra", "id:s:$id"
        )
        println("Enable integration '$id': $result")
    }

    private fun approveAuth(displayCode: String) {
        val result = adb(
            "shell", "content", "call",
            "--uri", "content://com.rousecontext.debug.test",
            "--method", "approve_auth",
            "--extra", "code:s:$displayCode"
        )
        println("Approve auth '$displayCode': $result")
    }
}
