package com.rousecontext.mcp.core

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parseUrlEncodedParameters
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import io.modelcontextprotocol.kotlin.sdk.server.Server
import java.io.IOException
import java.net.Socket
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.io.readByteArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/token` bounds the request body it will accept (#761).
 *
 * `/register` and `/mcp` have always bounded their body reads; `/token` was the
 * one body-reading endpoint in [configureMcpRouting] that read an unbounded
 * body into memory before parsing could reject anything. These tests pin the
 * bound on BOTH branches -- form-encoded and JSON -- because form-encoded is
 * the default for OAuth clients, so a JSON-only cap would leave the common
 * path open.
 *
 * Each rejection test is paired with a positive control at a realistic size.
 * Without the control, an implementation that rejected every request would
 * pass.
 */
class TokenBodyLimitTest {

    /**
     * The cap under test, in bytes. Mirrors `MAX_TOKEN_BODY_BYTES` in
     * McpRouting.kt, which `production cap is the value these tests exercise`
     * pins to this number so the two cannot drift apart silently.
     */
    private val cap = 4096

    private fun stubProvider(): McpServerProvider = object : McpServerProvider {
        override val id = "health"
        override val displayName = "Health Connect"
        override fun register(server: Server) = Unit
    }

    /**
     * Drives a `/token` request against a fully wired routing stack and returns
     * the response status and body.
     *
     * The body is passed through verbatim, so a test can send a body of an
     * exact byte length. No `runCatching` and no broad `catch` anywhere in this
     * harness: a failure inside the call under test must surface as a failing
     * test, not as a swallowed exception that lets the assertion below it pass.
     */
    private fun tokenRequest(
        contentType: ContentType,
        body: String,
        seed: (AuthorizationCodeManager, TokenStore) -> Unit = { _, _ -> },
        assert: (HttpStatusCode, String) -> Unit
    ) = testApplication {
        val registry = InMemoryProviderRegistry()
        registry.register("health", stubProvider())
        registry.setEnabled("health", true)
        val tokenStore = InMemoryTokenStore()
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)
        val authorizationCodeManager = AuthorizationCodeManager(tokenStore = tokenStore)
        seed(authorizationCodeManager, tokenStore)

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                authorizationCodeManager = authorizationCodeManager,
                hostname = "test.rousecontext.com",
                integration = "health",
                serverVersion = TEST_SERVER_VERSION
            )
        }

        val response = client.post("/token") {
            contentType(contentType)
            setBody(body)
        }
        assert(response.status, response.bodyAsText())
    }

    /** Pads [prefix] with a trailing junk parameter until it is exactly [size] bytes. */
    private fun formBodyOfSize(size: Int): String {
        val prefix = "grant_type=refresh_token&refresh_token=nonexistent&pad="
        require(prefix.length <= size) { "prefix already exceeds $size" }
        return prefix + "a".repeat(size - prefix.length)
    }

    /** Pads a syntactically valid JSON token request until it is exactly [size] bytes. */
    private fun jsonBodyOfSize(size: Int): String {
        val prefix =
            """{"grant_type":"refresh_token","refresh_token":"nonexistent","pad":""""
        val suffix = """"}"""
        require(prefix.length + suffix.length <= size) { "envelope already exceeds $size" }
        return prefix + "a".repeat(size - prefix.length - suffix.length) + suffix
    }

    // -- Rejection: form-encoded --

    @Test
    fun `form-encoded body one byte over the cap is rejected`() {
        val body = formBodyOfSize(cap + 1)
        assertEquals("test bug: body is not cap+1 bytes", cap + 1, body.toByteArray().size)
        tokenRequest(ContentType.Application.FormUrlEncoded, body) { status, _ ->
            assertEquals(
                "an over-cap form-encoded /token body must be rejected as too large",
                HttpStatusCode.PayloadTooLarge,
                status
            )
        }
    }

    @Test
    fun `form-encoded body far over the cap is rejected`() {
        tokenRequest(
            ContentType.Application.FormUrlEncoded,
            formBodyOfSize(cap * 64)
        ) { status, _ ->
            assertEquals(HttpStatusCode.PayloadTooLarge, status)
        }
    }

    // -- Rejection: JSON --

    @Test
    fun `json body one byte over the cap is rejected`() {
        val body = jsonBodyOfSize(cap + 1)
        assertEquals("test bug: body is not cap+1 bytes", cap + 1, body.toByteArray().size)
        tokenRequest(ContentType.Application.Json, body) { status, _ ->
            assertEquals(
                "an over-cap JSON /token body must be rejected as too large",
                HttpStatusCode.PayloadTooLarge,
                status
            )
        }
    }

    @Test
    fun `json body far over the cap is rejected`() {
        tokenRequest(ContentType.Application.Json, jsonBodyOfSize(cap * 64)) { status, _ ->
            assertEquals(HttpStatusCode.PayloadTooLarge, status)
        }
    }

    // -- Positive controls --

    /**
     * Seeds an approved authorization code and returns the parts a real client
     * would post back. RFC 7636 Appendix B vectors, as used by
     * [AuthorizationCodeFlowTest].
     */
    private val rfcCodeVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    private val rfcCodeChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
    private val redirectUri = "http://localhost:3000/callback"

    private fun approvedCode(manager: AuthorizationCodeManager): String {
        manager.registerClient("test-client", "Test", listOf(redirectUri))
        val request = manager.createRequest(
            clientId = "test-client",
            codeChallenge = rfcCodeChallenge,
            codeChallengeMethod = "S256",
            redirectUri = redirectUri,
            state = "state",
            integration = "health"
        )
        assertTrue("seed failed: code was not approved", manager.approve(request.displayCode))
        val status = manager.getStatus(request.requestId)
        assertTrue(
            "seed failed: status was $status, expected Approved",
            status is AuthorizationRequestStatus.Approved
        )
        return (status as AuthorizationRequestStatus.Approved).code
    }

    @Test
    fun `normal form-encoded authorization_code exchange still succeeds`() {
        testApplication {
            val registry = InMemoryProviderRegistry()
            registry.register("health", stubProvider())
            registry.setEnabled("health", true)
            val tokenStore = InMemoryTokenStore()
            val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)
            val authorizationCodeManager = AuthorizationCodeManager(tokenStore = tokenStore)
            val code = approvedCode(authorizationCodeManager)

            application {
                configureMcpRouting(
                    registry = registry,
                    tokenStore = tokenStore,
                    deviceCodeManager = deviceCodeManager,
                    authorizationCodeManager = authorizationCodeManager,
                    hostname = "test.rousecontext.com",
                    integration = "health",
                    serverVersion = TEST_SERVER_VERSION
                )
            }

            val body = "grant_type=authorization_code" +
                "&code=$code" +
                "&code_verifier=$rfcCodeVerifier" +
                "&redirect_uri=http%3A%2F%2Flocalhost%3A3000%2Fcallback"
            assertTrue(
                "a realistic form-encoded token request must fit under the cap, was " +
                    "${body.toByteArray().size} bytes",
                body.toByteArray().size < cap
            )

            val response = client.post("/token") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(body)
            }
            assertEquals(
                "a normal-sized form-encoded exchange must still succeed; body was " +
                    response.bodyAsText(),
                HttpStatusCode.OK,
                response.status
            )
            assertTrue(
                "response must carry an access token: ${response.bodyAsText()}",
                response.bodyAsText().contains("access_token")
            )
        }
    }

    @Test
    fun `normal json token request still reaches the grant handler`() {
        // A JSON device-code poll for an unknown code: the body is parsed and the
        // grant handler answers with an OAuth error, which proves the body was
        // read and decoded rather than rejected by the cap.
        val body = """{"grant_type":"urn:ietf:params:oauth:grant-type:device_code",""" +
            """"device_code":"nonexistent"}"""
        assertTrue(body.toByteArray().size < cap)
        tokenRequest(ContentType.Application.Json, body) { status, text ->
            assertTrue(
                "a normal-sized JSON token request must not be rejected as too large",
                status != HttpStatusCode.PayloadTooLarge
            )
            assertTrue(
                "the grant handler must have seen the decoded body, got $status / $text",
                text.contains("error")
            )
        }
    }

    // -- Boundary: exactly at the cap is still accepted --

    @Test
    fun `form-encoded body exactly at the cap is accepted`() {
        val body = formBodyOfSize(cap)
        assertEquals(cap, body.toByteArray().size)
        tokenRequest(ContentType.Application.FormUrlEncoded, body) { status, _ ->
            assertTrue(
                "a body exactly at the cap must not be rejected as too large",
                status != HttpStatusCode.PayloadTooLarge
            )
        }
    }

    @Test
    fun `json body exactly at the cap is accepted`() {
        val body = jsonBodyOfSize(cap)
        assertEquals(cap, body.toByteArray().size)
        tokenRequest(ContentType.Application.Json, body) { status, _ ->
            assertTrue(
                "a body exactly at the cap must not be rejected as too large",
                status != HttpStatusCode.PayloadTooLarge
            )
        }
    }

    // -- Chunked / undeclared length, against the engine that actually ships --
    //
    // The `Content-Length` pre-check is only the cheap half of the guard, and a
    // chunked body never reaches it. Ktor's `testApplication` client gives a
    // `WriteChannelContent` body a `Content-Length` anyway, so a test written
    // against it passes with the streaming read REMOVED -- measured, not
    // assumed. This one therefore drives a real CIO server (the engine the app
    // runs) over a raw socket and frames the chunks by hand, which is the only
    // way to reach the streaming path.

    /**
     * Starts a real CIO server carrying the MCP routing and hands [block] its
     * port. `port = 0` so concurrent test runs cannot collide on a fixed port.
     */
    private fun withCioServer(block: (Int) -> Unit) = runBlocking {
        val registry = InMemoryProviderRegistry()
        registry.register("health", stubProvider())
        registry.setEnabled("health", true)
        val tokenStore = InMemoryTokenStore()
        val server = embeddedServer(CIO, port = 0) {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore),
                hostname = "test.rousecontext.com",
                integration = "health",
                serverVersion = TEST_SERVER_VERSION
            )
        }
        server.start(wait = false)
        try {
            block(server.engine.resolvedConnectors().first().port)
        } finally {
            server.stop(0, 0)
        }
    }

    /** What a hand-framed chunked POST to `/token` produced. */
    private data class ChunkedResult(
        /** The HTTP status line the server sent back. */
        val statusLine: String,
        /** Body bytes the client managed to write before the server answered or hung up. */
        val bodyBytesWritten: Int,
        /** Body bytes the client intended to send. */
        val bodyBytesOffered: Int
    )

    /**
     * Sends a hand-framed `Transfer-Encoding: chunked` POST to `/token`,
     * stopping as soon as the server answers or closes the connection.
     *
     * Reporting [ChunkedResult.bodyBytesWritten] is the point of this harness.
     * The status line alone cannot distinguish a server that buffered the whole
     * body and then rejected it from one that stopped at the cap -- both answer
     * 413 -- and it is the second that this fix is about. A server that stops
     * reading answers while the client is still writing; a server that buffers
     * cannot answer until the last chunk is in.
     */
    private fun postChunked(port: Int, bodyBytes: Int): ChunkedResult {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            out.write(
                (
                    "POST /token HTTP/1.1\r\n" +
                        "Host: test.rousecontext.com\r\n" +
                        "Content-Type: application/x-www-form-urlencoded\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray()
            )
            out.flush()

            val body = formBodyOfSize(bodyBytes).toByteArray()
            var offset = 0
            // A broken pipe is TOLERATED here, narrowly and on the writes only:
            // the server closing mid-stream is the behaviour under test, so an
            // IOException while still pushing bytes is a pass signal. Every
            // assertion lives in the callers, outside this try.
            try {
                while (offset < body.size && input.available() == 0) {
                    val n = minOf(CHUNK_BYTES, body.size - offset)
                    out.write("${Integer.toHexString(n)}\r\n".toByteArray())
                    out.write(body, offset, n)
                    out.write("\r\n".toByteArray())
                    out.flush()
                    offset += n
                }
                if (offset >= body.size) {
                    out.write("0\r\n\r\n".toByteArray())
                    out.flush()
                }
            } catch (_: IOException) {
                // Server hung up early; read whatever it sent below.
            }

            val statusLine = input.bufferedReader().readLine()
                ?: error("server closed without sending a status line")
            return ChunkedResult(statusLine, offset, body.size)
        }
    }

    @Test
    fun `chunked over-cap body is rejected by the CIO engine that ships`() = withCioServer { port ->
        val result = postChunked(port, cap * 1024)
        assertTrue(
            "a chunked over-cap body must be rejected with 413; status line was: " +
                result.statusLine,
            result.statusLine.contains("413")
        )
    }

    @Test
    fun `bounded read stops at the cap instead of draining the channel`() = runBlocking {
        // The memory property, which no black-box HTTP test can show: after the
        // handler answers 413, the CIO engine drains whatever is left of the
        // request body to keep the connection sane, so a client measuring bytes
        // accepted off the socket sees the whole body either way. Measured, not
        // assumed -- an earlier version of this test asserted the client-side
        // count and failed at 4194304 of 4194304 bytes with the fix in place.
        //
        // What actually matters is how much the READER accumulates, so it is
        // asserted directly on `readBounded`: the bytes it leaves behind prove
        // it stopped at the cap rather than reading to the end.
        val total = 4 * 1024 * 1024
        val channel = ByteReadChannel(ByteArray(total) { 'a'.code.toByte() })

        assertNull(
            "a channel holding more than the cap must be reported as over-size",
            channel.readBounded(MAX_TOKEN_BODY_BYTES)
        )

        val leftBehind = channel.readRemaining().readByteArray().size
        assertEquals(
            "readBounded must consume exactly the cap plus one sentinel byte, " +
                "leaving the rest unread",
            total - (MAX_TOKEN_BODY_BYTES + 1),
            leftBehind
        )
    }

    @Test
    fun `bounded read returns a body that fits`() = runBlocking {
        // Control: without it, "return null always" would pass the test above.
        val payload = ByteArray(MAX_TOKEN_BODY_BYTES) { 'b'.code.toByte() }
        val channel = ByteReadChannel(payload)
        val read = channel.readBounded(MAX_TOKEN_BODY_BYTES)
        assertNotNull("a body exactly at the cap must be returned", read)
        assertEquals(MAX_TOKEN_BODY_BYTES, read!!.size)
    }

    @Test
    fun `an over-size Content-Length is refused before any body arrives`() {
        // Pins the cheap half of the guard. The request declares a 4 MB body and
        // then sends NOTHING. With the Content-Length pre-check the server
        // answers 413 straight away; without it the bounded read would sit
        // waiting for bytes that never come and eventually answer 400 on the
        // read timeout. The two are told apart by BOTH the status and the wait.
        withCioServer { port ->
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = SOCKET_TIMEOUT_MS
                val out = socket.getOutputStream()
                out.write(
                    (
                        "POST /token HTTP/1.1\r\n" +
                            "Host: test.rousecontext.com\r\n" +
                            "Content-Type: application/x-www-form-urlencoded\r\n" +
                            "Content-Length: 4194304\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray()
                )
                out.flush()

                val startedAt = System.nanoTime()
                val statusLine = socket.getInputStream().bufferedReader().readLine()
                    ?: error("server closed without sending a status line")
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

                assertTrue(
                    "a declared over-size body must be refused as too large; was: $statusLine",
                    statusLine.contains("413")
                )
                assertTrue(
                    "the refusal must not wait for a body that never arrives; took ${elapsedMs}ms",
                    elapsedMs < BODY_READ_TIMEOUT_MS / 2
                )
            }
        }
    }

    @Test
    fun `chunked under-cap body is not rejected`() {
        // The control: without it, refusing every chunked request would pass.
        // A well-formed refresh grant for an unknown token answers 400, which
        // proves the body was read and parsed rather than rejected on size.
        withCioServer { port ->
            val result = postChunked(port, 128)
            assertEquals(
                "an under-cap chunked body must be delivered in full",
                result.bodyBytesOffered,
                result.bodyBytesWritten
            )
            assertTrue(
                "an under-cap chunked body must not be rejected as too large; was: " +
                    result.statusLine,
                !result.statusLine.contains("413")
            )
            assertTrue(
                "the grant handler must have answered; status line was: ${result.statusLine}",
                result.statusLine.contains("400")
            )
        }
    }

    // -- The production cap, and the measurements that justify it --

    @Test
    fun `production cap is the value these tests exercise`() {
        assertEquals(
            "MAX_TOKEN_BODY_BYTES changed; re-derive the justification before moving it",
            cap,
            MAX_TOKEN_BODY_BYTES
        )
    }

    @Test
    fun `every OAuth credential this server issues is 43 base64url characters`() {
        // The cap is derived from these lengths, so they are measured against the
        // real generators rather than restated from the comment on the constant.
        val tokenStore = InMemoryTokenStore()
        val code = approvedCode(AuthorizationCodeManager(tokenStore = tokenStore))
        assertEquals("authorization code (32 random bytes, base64url)", 43, code.length)

        val deviceCode = DeviceCodeManager(tokenStore = tokenStore)
            .authorize("health")
            .deviceCode
        assertEquals("device code (32 random bytes, base64url)", 43, deviceCode.length)

        assertEquals("client_id is a UUID", 36, UUID.randomUUID().toString().length)
    }

    @Test
    fun `largest token request the spec permits is far under the cap`() {
        // The worst legitimate case: an authorization_code grant carrying a
        // code_verifier at RFC 7636 section 4.1's 128-character maximum, a UUID
        // client_id, and a redirect_uri.
        val tokenStore = InMemoryTokenStore()
        val code = approvedCode(AuthorizationCodeManager(tokenStore = tokenStore))
        val body = "grant_type=authorization_code" +
            "&code=$code" +
            "&code_verifier=" + "a".repeat(128) +
            "&client_id=" + UUID.randomUUID() +
            "&redirect_uri=http%3A%2F%2Flocalhost%3A3000%2Fcallback"

        val size = body.toByteArray().size
        assertTrue(
            "worst-case authorization_code request measured $size bytes; the cap of " +
                "$cap must keep at least 8x headroom over it",
            size * 8 <= cap
        )
    }

    @Test
    fun `device code poll and refresh grants are a few hundred bytes`() {
        val tokenStore = InMemoryTokenStore()
        val deviceCode = DeviceCodeManager(tokenStore = tokenStore)
            .authorize("health")
            .deviceCode
        val poll = "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code" +
            "&device_code=$deviceCode" +
            "&client_id=" + UUID.randomUUID()
        val refresh = "grant_type=refresh_token" +
            "&refresh_token=" + "a".repeat(43) +
            "&client_id=" + UUID.randomUUID()

        assertTrue(
            "device-code poll measured ${poll.toByteArray().size} bytes",
            poll.toByteArray().size < 256
        )
        assertTrue(
            "refresh measured ${refresh.toByteArray().size} bytes",
            refresh.toByteArray().size < 256
        )
    }

    // -- The parser substitution --

    @Test
    fun `bounded form decoding matches Ktor receiveParameters`() = testApplication {
        // The fix stopped calling `receiveParameters()` on the form branch --
        // it reads the channel itself and so cannot be capped -- and parses the
        // bounded text with `parseUrlEncodedParameters` instead. If those two
        // decoded differently, the cap would have silently changed the meaning
        // of every form-encoded token request. They are compared here on the
        // cases where URL decoders disagree: `+`, `%2B`, `%20`, an escaped
        // separator, an empty value, and non-ASCII.
        val raw = "a=hello+world&b=%2Bplus&c=%20space&d=a%26b&e=&f=caf%C3%A9"

        application {
            routing {
                post("/echo") {
                    val p = call.receiveParameters()
                    call.respondText(p.names().sorted().joinToString("|") { "$it=${p[it]}" })
                }
            }
        }

        val viaKtor = client.post("/echo") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(raw)
        }.bodyAsText()

        val parsed = raw.parseUrlEncodedParameters(plusIsSpace = true)
        val viaBounded = parsed.names().sorted().joinToString("|") { "$it=${parsed[it]}" }

        assertEquals(
            "the bounded parser must decode a form body exactly as receiveParameters does",
            viaKtor,
            viaBounded
        )
        assertTrue("guard against both sides being empty", viaKtor.contains("hello world"))
    }

    private companion object {
        /** Body bytes per HTTP chunk. Small enough that the server can reject mid-stream. */
        const val CHUNK_BYTES = 512

        /** Read timeout on the test socket, so a hang fails instead of blocking the suite. */
        const val SOCKET_TIMEOUT_MS = 15_000

        /** Mirrors `TOKEN_BODY_TIMEOUT_MS` in McpRouting.kt, which is private. */
        const val BODY_READ_TIMEOUT_MS = 5_000
    }
}
