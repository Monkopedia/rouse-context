package com.rousecontext.e2e

import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
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
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * Cold-start-via-FCM end-to-end test.
 *
 * Kills the app process (simulating an OOM kill / swipe-from-Recents lifecycle),
 * then hits the MCP endpoint over HTTPS. The relay delivers an FCM high-priority
 * message which wakes the device from cold, establishes the tunnel, and serves
 * the MCP request. Measures wall-clock latency.
 *
 * Uses `cmd activity stop-app` rather than `am force-stop` or `am kill`:
 * - `am force-stop` puts the app into Android's "stopped" state which
 *   intentionally blocks FCM delivery until manual user relaunch — not
 *   reachable from real user scenarios (OOM kill, reboot, swipe-from-Recents
 *   all leave FCM reachable).
 * - `am kill` refuses to kill processes that hold a foreground service (which
 *   `TunnelForegroundService` does whenever the tunnel is connecting/connected),
 *   so it's a no-op in this test's most common scenario.
 * - `cmd activity stop-app` (Android 11+) kills the process including any
 *   foreground service, leaves the app in the normal non-stopped state, and
 *   FCM can still wake it. This is the closest simulation of an OOM kill or
 *   user-swipe-from-Recents. See #394.
 *
 * Requires:
 * - A debug build installed and onboarded on a device
 * - Device connected via adb through <your-dev-host>
 * - The relay running at relay.rousecontext.com
 * - FCM configured (real Firebase project, not test)
 *
 * Run with:
 * ```
 * ./gradlew :e2e:e2eTest -Dadb.host=<your-dev-host>
 * ```
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ColdStartEndToEndTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val adbHost = System.getProperty("adb.host")
        ?: error("adb.host system property required, e.g. -Dadb.host=<your-dev-host>")
    private val integrationId = System.getProperty("mcp.integration", "test")

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private lateinit var mcpUrl: String
    private lateinit var baseUrl: String
    private lateinit var accessToken: String
    private var mcpSessionId: String? = null

    @BeforeAll
    fun setup() {
        // Skip if no device reachable
        assumeTrue(deviceIsConnected(), "No device connected via adb")

        // Ensure app is running so we can read device state
        adb(
            "shell",
            "am",
            "start",
            "-n",
            "com.rousecontext.debug/com.rousecontext.app.MainActivity"
        )
        Thread.sleep(3000)

        // Check device is onboarded
        val subdomain = getDeviceSubdomain()
        assumeTrue(subdomain != null, "Device not onboarded (no subdomain)")

        // Enable integration and discover MCP URL
        enableIntegration(integrationId)
        Thread.sleep(1000)

        val explicitUrl = System.getProperty("mcp.url", "")
        mcpUrl = if (explicitUrl.isNotEmpty()) {
            explicitUrl
        } else {
            discoverMcpUrl(integrationId)
        }
        baseUrl = mcpUrl.removeSuffix("/mcp")
        println("Cold-start test MCP URL: $mcpUrl")

        // Perform OAuth flow while app is warm to get a valid access token
        accessToken = performOAuthFlow()
        println("Access token obtained: ${accessToken.take(10)}...")
    }

    @Test
    @Order(1)
    fun `01 - cold start via FCM wake succeeds within latency budget`() {
        // Kill the process via `cmd activity stop-app` — kills the foreground
        // service holder too, but leaves the app in the normal non-stopped
        // state so FCM can still wake it (unlike `am force-stop`). See class
        // kdoc + #394 for why other primitives don't work here.
        adb("shell", "cmd", "activity", "stop-app", "com.rousecontext.debug")

        // Wait for process death. Polls batched on the remote host to keep
        // SSH overhead (~200ms/call) from eating the deadline (#394).
        waitForProcessDeath(timeoutSec = 10)

        // Extra buffer for Android to clean up process state
        Thread.sleep(2000)

        // Verify process is actually dead
        val pidCheck = adb("shell", "pidof", "com.rousecontext.debug")
        assertTrue(pidCheck.isBlank(), "Process still running after stop-app: '$pidCheck'")

        // Hit the MCP endpoint — triggers relay -> FCM -> cold device wake
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
                            put("name", "E2E Cold Start Test")
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

        val startMs = System.currentTimeMillis()
        client.newCall(request).execute().use { response ->
            val latencyMs = System.currentTimeMillis() - startMs
            val bodyText = response.body?.string() ?: ""

            println("Cold-start-via-FCM latency: ${latencyMs}ms")
            println("Response code: ${response.code}")

            assertTrue(
                response.code in listOf(200, 401),
                "Expected 200 or 401, got ${response.code}: $bodyText"
            )
            assertTrue(
                latencyMs < 20_000,
                "Cold start latency ${latencyMs}ms exceeded 20s budget"
            )

            if (response.code == 200) {
                mcpSessionId = response.header("Mcp-Session-Id")
                val body = json.parseToJsonElement(bodyText).jsonObject
                val result = body["result"]?.jsonObject
                assertNotNull(result, "Missing result in initialize response: $bodyText")
                println("Server initialized successfully after cold start")
            }
        }
    }

    @Test
    @Order(2)
    fun `02 - warm start after cold start is fast`() {
        // This test depends on test 01 having succeeded (tunnel is now warm)
        assumeTrue(mcpSessionId != null, "Cold start test did not produce a session")

        // Send a tools/list request — tunnel is already up, no FCM wake needed
        val listBody = buildJsonObject {
            put("method", "tools/list")
            put("jsonrpc", "2.0")
            put("id", 2)
        }

        val request = Request.Builder()
            .url(mcpUrl)
            .post(listBody.toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $accessToken")
            .header("Mcp-Session-Id", mcpSessionId!!)
            .build()

        val startMs = System.currentTimeMillis()
        client.newCall(request).execute().use { response ->
            val latencyMs = System.currentTimeMillis() - startMs
            val bodyText = response.body?.string() ?: ""

            println("Warm-start latency: ${latencyMs}ms")
            println("Response code: ${response.code}")

            assertTrue(
                response.code == 200,
                "Expected 200 for warm request, got ${response.code}: $bodyText"
            )
            assertTrue(
                latencyMs < 3_000,
                "Warm start latency ${latencyMs}ms exceeded 3s budget " +
                    "(tunnel should already be up)"
            )

            val body = json.parseToJsonElement(bodyText).jsonObject
            val tools = body["result"]?.jsonObject?.get("tools")?.jsonArray
            assertNotNull(tools, "Missing tools in warm-start response")
            assertTrue(tools!!.isNotEmpty(), "No tools returned on warm start")
            println("Warm start returned ${tools.size} tools in ${latencyMs}ms")
        }
    }

    // --- OAuth flow ---

    private fun performOAuthFlow(): String {
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

        // Register client
        val registerBody = buildJsonObject {
            put(
                "redirect_uris",
                buildJsonArray {
                    add(JsonPrimitive("http://localhost:9999/callback"))
                }
            )
            put("client_name", "E2E Cold Start Test")
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
            "state=e2etest-coldstart"

        val authRequest = Request.Builder().url(authUrl).get().build()
        val authCode = client.newCall(authRequest).execute().use { response ->
            val html = response.body!!.string()
            require(response.code == 200) {
                "Authorization page failed: $html"
            }

            val codePattern = Regex("""([A-Z0-9]{6}-[A-Z0-9]{6})""")
            val match = codePattern.find(html)
                ?: error("Could not find display code in authorize page")
            val displayCode = match.value
            println("Display code: $displayCode")

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
                    if (status == "approved") {
                        code = statusBody["code"]?.jsonPrimitive?.content
                    }
                }
                if (code != null) break
            }
            requireNotNull(code) { "Authorization was not approved within 10 seconds" }
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
            require(response.code == 200) { "Token exchange failed: $body" }
            body["access_token"]!!.jsonPrimitive.content
        }
    }

    // --- Device helpers ---

    /**
     * Poll `pidof` on the device until the process dies or [timeoutSec] elapses.
     * Runs the whole poll loop in a single SSH shell to avoid paying the
     * per-call SSH round-trip (~200ms, #394).
     */
    private fun waitForProcessDeath(timeoutSec: Int) {
        val iters = timeoutSec * 2 // poll every 500ms
        val script = (1..iters).joinToString("; ") {
            "pidof com.rousecontext.debug || exit 0; sleep 0.5"
        } + "; exit 1"
        val serial = System.getProperty("adb.serial", "")
        val remoteAdb = if (serial.isNotEmpty()) {
            "ANDROID_SERIAL=$serial /opt/android-sdk/platform-tools/adb"
        } else {
            "/opt/android-sdk/platform-tools/adb"
        }
        val cmd = listOf("ssh", adbHost, "$remoteAdb shell '$script'")
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().readText()
        process.waitFor((timeoutSec + 5).toLong(), TimeUnit.SECONDS)
        assertTrue(
            process.exitValue() == 0,
            "Process did not die within ${timeoutSec}s (exit=${process.exitValue()})"
        )
    }

    private fun deviceIsConnected(): Boolean = try {
        val result = adb("devices")
        // Look for at least one device line (not just "List of devices attached")
        result.lines().any { line ->
            line.contains("\tdevice") || line.contains("\tunauthorized")
        }
    } catch (_: Exception) {
        false
    }

    private fun getDeviceSubdomain(): String? = try {
        val subdomain = adb(
            "shell",
            "run-as",
            "com.rousecontext.debug",
            "cat",
            "files/rouse_subdomain.txt"
        ).trim()
        subdomain.ifEmpty { null }
    } catch (_: Exception) {
        null
    }

    private fun discoverMcpUrl(integration: String): String {
        val subdomain = getDeviceSubdomain()
            ?: error("No subdomain found on device")

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

    private fun adb(vararg args: String): String {
        val serial = System.getProperty("adb.serial", "")
        val remoteAdb = if (serial.isNotEmpty()) {
            "ANDROID_SERIAL=$serial /opt/android-sdk/platform-tools/adb"
        } else {
            "/opt/android-sdk/platform-tools/adb"
        }
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
