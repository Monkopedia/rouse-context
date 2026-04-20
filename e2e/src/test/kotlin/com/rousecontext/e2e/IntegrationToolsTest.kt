package com.rousecontext.e2e

import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.stream.Stream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Per-integration E2E test that auto-discovers all integrations from the device
 * and verifies each one exposes the expected tools.
 *
 * Run with:
 * ```
 * ./gradlew :e2e:e2eTest -Dadb.host=<your-dev-host>
 * ```
 */
class IntegrationToolsTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val adbHost = System.getProperty("adb.host")
        ?: error("adb.host system property required, e.g. -Dadb.host=<your-dev-host>")

    /**
     * Expected tools per integration. Integrations not listed here still get a
     * basic "returns at least 1 tool" assertion.
     */
    private val expectedTools: Map<String, List<String>> = mapOf(
        "test" to listOf("echo", "get_time", "device_info"),
        "outreach" to listOf(
            "launch_app",
            "open_link",
            "copy_to_clipboard",
            "send_notification",
            "list_installed_apps"
        ),
        "notifications" to listOf(
            "list_active_notifications",
            "perform_notification_action",
            "dismiss_notification",
            "search_notification_history",
            "get_notification_stats"
        ),
        "usage" to listOf(
            "get_usage_summary",
            "get_app_usage",
            "compare_usage",
            "get_usage_events"
        ),
        "health" to listOf(
            "list_record_types",
            "query_health_data",
            "get_health_summary"
        )
    )

    @TestFactory
    fun `each integration exposes expected tools`(): Stream<DynamicTest> {
        val integrations = discoverIntegrations()
        println("Discovered integrations: ${integrations.keys}")

        return integrations.entries.stream().map { (integrationId, secret) ->
            DynamicTest.dynamicTest("integration '$integrationId' tools") {
                runIntegrationTest(integrationId, secret)
            }
        }
    }

    private fun runIntegrationTest(integrationId: String, secret: String) {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()

        // Ensure app is running
        adb(
            "shell",
            "am",
            "start",
            "-n",
            "com.rousecontext.debug/com.rousecontext.app.MainActivity"
        )
        Thread.sleep(2000)

        // Enable integration
        enableIntegration(integrationId)
        Thread.sleep(1000)

        // Build MCP URL
        val subdomain = adb(
            "shell",
            "run-as",
            "com.rousecontext.debug",
            "cat",
            "files/rouse_subdomain.txt"
        ).trim()
        require(subdomain.isNotEmpty()) { "No subdomain found on device" }
        val baseUrl = "https://$secret.$subdomain.rousecontext.com"
        val mcpUrl = "$baseUrl/mcp"
        println("[$integrationId] MCP URL: $mcpUrl")

        // OAuth flow: discover -> register -> authorize -> token
        val accessToken = performOAuthFlow(client, baseUrl, integrationId)

        // MCP initialize
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
                            put("name", "E2E Integration Test")
                            put("version", "1.0.0")
                        }
                    )
                }
            )
        }

        val initRequest = Request.Builder()
            .url(mcpUrl)
            .post(initBody.toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $accessToken")
            .build()

        var mcpSessionId: String? = null
        client.newCall(initRequest).execute().use { response ->
            val bodyText = response.body?.string() ?: ""
            if (response.code != 200) {
                fail<Unit>("[$integrationId] MCP initialize failed (${response.code}): $bodyText")
            }
            mcpSessionId = response.header("Mcp-Session-Id")
        }

        // tools/list
        val listBody = buildJsonObject {
            put("method", "tools/list")
            put("jsonrpc", "2.0")
            put("id", 2)
        }

        val listRequestBuilder = Request.Builder()
            .url(mcpUrl)
            .post(listBody.toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $accessToken")
        if (mcpSessionId != null) {
            listRequestBuilder.header("Mcp-Session-Id", mcpSessionId!!)
        }
        val listRequest = listRequestBuilder.build()

        client.newCall(listRequest).execute().use { response ->
            val bodyText = response.body?.string() ?: ""
            if (response.code != 200) {
                fail<Unit>("[$integrationId] tools/list failed (${response.code}): $bodyText")
            }
            val body = json.parseToJsonElement(bodyText).jsonObject
            val tools = body["result"]?.jsonObject?.get("tools")?.jsonArray
            assertNotNull(tools, "[$integrationId] Missing tools in response")
            assertTrue(tools!!.isNotEmpty(), "[$integrationId] No tools returned")

            val toolNames = tools.map {
                it.jsonObject["name"]?.jsonPrimitive?.content ?: ""
            }
            println("[$integrationId] Tools: $toolNames")

            // Check expected tools for this integration
            val expected = expectedTools[integrationId]
            if (expected != null) {
                for (toolName in expected) {
                    assertTrue(
                        toolNames.contains(toolName),
                        "[$integrationId] Missing expected tool '$toolName'. " +
                            "Available: $toolNames"
                    )
                }
            }
        }
    }

    private fun performOAuthFlow(
        client: OkHttpClient,
        baseUrl: String,
        integrationId: String
    ): String {
        // PKCE
        val random = SecureRandom()
        val verifierBytes = ByteArray(32)
        random.nextBytes(verifierBytes)
        val codeVerifier = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(verifierBytes)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(codeVerifier.toByteArray())
        val codeChallenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(digest)

        // Discover protected resource
        val prRequest = Request.Builder()
            .url("$baseUrl/.well-known/oauth-protected-resource")
            .get().build()
        val authServerUrl = client.newCall(prRequest).execute().use { response ->
            val body = json.parseToJsonElement(response.body!!.string()).jsonObject
            body["authorization_servers"]!!.jsonArray[0].jsonPrimitive.content
        }

        // Register client
        val registerBody = buildJsonObject {
            put(
                "redirect_uris",
                buildJsonArray {
                    add(JsonPrimitive("http://localhost:9999/callback"))
                }
            )
            put("client_name", "E2E Integration Test ($integrationId)")
            put("token_endpoint_auth_method", "client_secret_post")
            put(
                "grant_types",
                buildJsonArray {
                    add(JsonPrimitive("authorization_code"))
                    add(JsonPrimitive("refresh_token"))
                }
            )
            put(
                "response_types",
                buildJsonArray {
                    add(JsonPrimitive("code"))
                }
            )
        }

        val regRequest = Request.Builder()
            .url("$baseUrl/register")
            .post(registerBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val (clientId, clientSecret) = client.newCall(regRequest).execute().use { response ->
            val body = json.parseToJsonElement(response.body!!.string()).jsonObject
            val id = body["client_id"]!!.jsonPrimitive.content
            val secret = body["client_secret"]?.jsonPrimitive?.content
            Pair(id, secret)
        }

        // Authorize
        val authUrl = "$baseUrl/authorize?" +
            "response_type=code&" +
            "client_id=$clientId&" +
            "redirect_uri=${URLEncoder.encode("http://localhost:9999/callback", "UTF-8")}&" +
            "code_challenge=$codeChallenge&" +
            "code_challenge_method=S256&" +
            "state=e2etest-$integrationId"

        val authRequest = Request.Builder().url(authUrl).get().build()
        val authCode = client.newCall(authRequest).execute().use { response ->
            val html = response.body!!.string()
            require(response.code == 200) {
                "[$integrationId] Authorization page failed: $html"
            }

            val codePattern = Regex("""([A-Z0-9]{6}-[A-Z0-9]{6})""")
            val match = codePattern.find(html)
                ?: error("[$integrationId] Could not find display code in authorize page")
            val displayCode = match.value
            println("[$integrationId] Display code: $displayCode")

            approveAuth(displayCode)

            val requestIdPattern = Regex("""request_id=([a-f0-9-]+)""")
            val requestId = requestIdPattern.find(html)!!.groupValues[1]

            var code: String? = null
            for (i in 1..10) {
                Thread.sleep(1000)
                val statusRequest = Request.Builder()
                    .url("$baseUrl/authorize/status?request_id=$requestId")
                    .get().build()

                client.newCall(statusRequest).execute().use { statusResponse ->
                    val statusBody = json.parseToJsonElement(
                        statusResponse.body!!.string()
                    ).jsonObject
                    val status = statusBody["status"]?.jsonPrimitive?.content
                    println("[$integrationId] Poll $i: status=$status")
                    if (status == "approved") {
                        code = statusBody["code"]?.jsonPrimitive?.content
                    }
                }
                if (code != null) break
            }
            requireNotNull(code) {
                "[$integrationId] Authorization was not approved within 10 seconds"
            }
            code!!
        }

        // Token exchange
        val formBody = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", authCode)
            .add("client_id", clientId)
            .add("code_verifier", codeVerifier)
            .add("redirect_uri", "http://localhost:9999/callback")
            .apply { clientSecret?.let { add("client_secret", it) } }
            .build()

        val tokenRequest = Request.Builder()
            .url("$baseUrl/token")
            .post(formBody)
            .build()

        return client.newCall(tokenRequest).execute().use { response ->
            val body = json.parseToJsonElement(response.body!!.string()).jsonObject
            require(response.code == 200) {
                "[$integrationId] Token exchange failed: $body"
            }
            body["access_token"]!!.jsonPrimitive.content
        }
    }

    private fun discoverIntegrations(): Map<String, String> {
        val secretsJson = adb(
            "shell",
            "run-as",
            "com.rousecontext.debug",
            "cat",
            "files/rouse_integration_secrets.json"
        ).trim()
        require(secretsJson.isNotEmpty()) { "No integration secrets found on device" }

        val secrets = json.parseToJsonElement(secretsJson).jsonObject
        return secrets.mapValues { (_, v) -> v.jsonPrimitive.content }
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
