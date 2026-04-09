package com.rousecontext.e2e

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

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

    // State accumulated across ordered tests
    private var authServerUrl: String? = null
    private var clientId: String? = null
    private var clientSecret: String? = null
    private var codeVerifier: String? = null
    private var codeChallenge: String? = null
    private var authCode: String? = null
    private var accessToken: String? = null

    @BeforeAll
    fun setup() {
        mcpUrl = System.getProperty("mcp.url")
        require(mcpUrl.isNotEmpty()) {
            "mcp.url system property required. Pass -Dmcp.url=https://..."
        }
        baseUrl = mcpUrl.removeSuffix("/mcp")
        adbHost = System.getProperty("adb.host", "adolin.lan")

        // Generate PKCE code verifier and challenge
        val random = SecureRandom()
        val verifierBytes = ByteArray(32)
        random.nextBytes(verifierBytes)
        codeVerifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier!!.toByteArray())
        codeChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)

        // Enable test integration via adb
        enableIntegration("test")
    }

    @Test
    @Order(1)
    fun `01 - discover OAuth protected resource`() {
        val request = Request.Builder()
            .url("$baseUrl/.well-known/oauth-protected-resource")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals(200, response.code, "Protected resource discovery failed: ${response.body?.string()}")
            val body = json.parseToJsonElement(response.body!!.string()).jsonObject
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
            assertEquals(200, response.code, "Auth server metadata failed: ${response.body?.string()}")
            val body = json.parseToJsonElement(response.body!!.string()).jsonObject
            assertNotNull(body["authorization_endpoint"], "Missing authorization_endpoint")
            assertNotNull(body["token_endpoint"], "Missing token_endpoint")
            assertNotNull(body["registration_endpoint"], "Missing registration_endpoint")
            println("Endpoints discovered: auth=${body["authorization_endpoint"]}, token=${body["token_endpoint"]}")
        }
    }

    @Test
    @Order(3)
    fun `03 - register client`() {
        val registerBody = buildJsonObject {
            put("redirect_uris", kotlinx.serialization.json.JsonArray(
                listOf(kotlinx.serialization.json.JsonPrimitive("http://localhost:9999/callback"))
            ))
            put("client_name", "E2E Test Client")
            put("token_endpoint_auth_method", "client_secret_post")
            put("grant_types", kotlinx.serialization.json.JsonArray(
                listOf(
                    kotlinx.serialization.json.JsonPrimitive("authorization_code"),
                    kotlinx.serialization.json.JsonPrimitive("refresh_token")
                )
            ))
            put("response_types", kotlinx.serialization.json.JsonArray(
                listOf(kotlinx.serialization.json.JsonPrimitive("code"))
            ))
        }

        val request = Request.Builder()
            .url("$baseUrl/register")
            .post(registerBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals(201, response.code, "Client registration failed: ${response.body?.string()}")
            val body = json.parseToJsonElement(response.body!!.string()).jsonObject
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
            "redirect_uri=${java.net.URLEncoder.encode("http://localhost:9999/callback", "UTF-8")}&" +
            "code_challenge=$codeChallenge&" +
            "code_challenge_method=S256&" +
            "state=e2etest"

        val request = Request.Builder().url(authUrl).get().build()

        client.newCall(request).execute().use { response ->
            assertEquals(200, response.code, "Authorization page failed: ${response.body?.string()}")
            val html = response.body!!.string()

            // Extract display code from HTML — look for the code pattern (e.g. AB3X-9K2F)
            val codePattern = Regex("""([A-Z0-9]{4}-[A-Z0-9]{4})""")
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
            assertEquals(200, response.code, "Token exchange failed: ${response.body?.string()}")
            val body = json.parseToJsonElement(response.body!!.string()).jsonObject
            accessToken = body["access_token"]?.jsonPrimitive?.content
            assertNotNull(accessToken, "Missing access_token")
            println("Access token obtained: ${accessToken!!.take(10)}...")
        }
    }

    @Test
    @Order(6)
    fun `06 - MCP initialize`() {
        val initBody = buildJsonObject {
            put("method", "initialize")
            put("jsonrpc", "2.0")
            put("id", 1)
            put("params", buildJsonObject {
                put("protocolVersion", "2025-11-25")
                put("capabilities", buildJsonObject {})
                put("clientInfo", buildJsonObject {
                    put("name", "E2E Test")
                    put("version", "1.0.0")
                })
            })
        }

        val request = Request.Builder()
            .url(mcpUrl)
            .post(initBody.toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $accessToken")
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals(200, response.code, "MCP initialize failed: ${response.body?.string()}")
            val body = json.parseToJsonElement(response.body!!.string()).jsonObject
            val result = body["result"]?.jsonObject
            assertNotNull(result, "Missing result in initialize response")
            val serverInfo = result!!["serverInfo"]?.jsonObject
            assertNotNull(serverInfo, "Missing serverInfo")
            println("Server: ${serverInfo!!["name"]?.jsonPrimitive?.content} ${serverInfo["version"]?.jsonPrimitive?.content}")
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
            assertEquals(200, response.code, "tools/list failed: ${response.body?.string()}")
            val body = json.parseToJsonElement(response.body!!.string()).jsonObject
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
            put("params", buildJsonObject {
                put("name", "echo")
                put("arguments", buildJsonObject {
                    put("message", "hello from e2e test")
                })
            })
        }

        val request = Request.Builder()
            .url(mcpUrl)
            .post(callBody.toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $accessToken")
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals(200, response.code, "tools/call failed: ${response.body?.string()}")
            val body = json.parseToJsonElement(response.body!!.string()).jsonObject
            val content = body["result"]?.jsonObject?.get("content")?.jsonArray
            assertNotNull(content, "Missing content in response")
            val text = content!![0].jsonObject["text"]?.jsonPrimitive?.content
            assertEquals("hello from e2e test", text, "Echo tool returned wrong content")
            println("Echo response: $text")
        }
    }

    // --- ADB helpers ---

    private fun adb(vararg args: String): String {
        val cmd = listOf("ssh", adbHost, "/opt/android-sdk/platform-tools/adb") + args.toList()
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
