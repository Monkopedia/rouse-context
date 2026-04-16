package com.rousecontext.mcp.core

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for issue #177: when an internal token is configured, every Ktor route
 * (including the well-known OAuth discovery endpoints) MUST reject requests
 * that lack the `X-Internal-Token` header. The only legitimate HTTP caller is
 * the in-process bridge, which injects the shared secret; other apps on the
 * device hitting loopback directly should be denied before any OAuth or
 * rate-limit logic runs.
 */
class InternalTokenGuardTest {

    private fun stubProvider(): McpServerProvider = object : McpServerProvider {
        override val id = "health"
        override val displayName = "Health Connect"
        override fun register(server: Server) = Unit
    }

    private val validToken = "correct-horse-battery-staple-internal-token"

    @Test
    fun `missing X-Internal-Token header returns 403 on authorize`() = testApplication {
        val registry = InMemoryProviderRegistry()
        registry.register("health", stubProvider())
        registry.setEnabled("health", true)
        val tokenStore = InMemoryTokenStore()
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                hostname = "test.rousecontext.com",
                integration = "health",
                internalToken = validToken
            )
        }

        val response = client.get("/authorize")
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(
            "Expected invalid_internal_token in body, got: ${response.bodyAsText()}",
            response.bodyAsText().contains("invalid_internal_token")
        )
    }

    @Test
    fun `wrong X-Internal-Token returns 403`() = testApplication {
        val registry = InMemoryProviderRegistry()
        registry.register("health", stubProvider())
        registry.setEnabled("health", true)
        val tokenStore = InMemoryTokenStore()
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                hostname = "test.rousecontext.com",
                integration = "health",
                internalToken = validToken
            )
        }

        val response = client.get("/authorize") {
            header("X-Internal-Token", "wrong-token")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `correct X-Internal-Token bypasses guard`() = testApplication {
        val registry = InMemoryProviderRegistry()
        registry.register("health", stubProvider())
        registry.setEnabled("health", true)
        val tokenStore = InMemoryTokenStore()
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                hostname = "test.rousecontext.com",
                integration = "health",
                internalToken = validToken
            )
        }

        // With the right token, /authorize without params returns 400 (bad request)
        // not 403. The key assertion is "not 403".
        val response = client.get("/authorize") {
            header("X-Internal-Token", validToken)
        }
        assertNotEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `well-known oauth metadata also requires token`() = testApplication {
        val registry = InMemoryProviderRegistry()
        registry.register("health", stubProvider())
        registry.setEnabled("health", true)
        val tokenStore = InMemoryTokenStore()
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                hostname = "test.rousecontext.com",
                integration = "health",
                internalToken = validToken
            )
        }

        val noToken = client.get("/.well-known/oauth-authorization-server")
        assertEquals(HttpStatusCode.Forbidden, noToken.status)

        val withToken = client.get("/.well-known/oauth-authorization-server") {
            header("X-Internal-Token", validToken)
        }
        assertEquals(HttpStatusCode.OK, withToken.status)
    }

    @Test
    fun `device authorize also requires token`() = testApplication {
        val registry = InMemoryProviderRegistry()
        registry.register("health", stubProvider())
        registry.setEnabled("health", true)
        val tokenStore = InMemoryTokenStore()
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                hostname = "test.rousecontext.com",
                integration = "health",
                internalToken = validToken
            )
        }

        val noToken = client.post("/device/authorize")
        assertEquals(HttpStatusCode.Forbidden, noToken.status)
    }

    @Test
    fun `token endpoint also requires token`() = testApplication {
        val registry = InMemoryProviderRegistry()
        registry.register("health", stubProvider())
        registry.setEnabled("health", true)
        val tokenStore = InMemoryTokenStore()
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                hostname = "test.rousecontext.com",
                integration = "health",
                internalToken = validToken
            )
        }

        val response = client.post("/token") {
            contentType(ContentType.Application.Json)
            setBody("""{"grant_type":"foo"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `mcp endpoint also requires token`() = testApplication {
        val registry = InMemoryProviderRegistry()
        registry.register("health", stubProvider())
        registry.setEnabled("health", true)
        val tokenStore = InMemoryTokenStore()
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                hostname = "test.rousecontext.com",
                integration = "health",
                internalToken = validToken
            )
        }

        // Without the internal token, even with bearer, we should get 403 BEFORE
        // auth is checked -- the guard runs first.
        val bearer = tokenStore.createTokenPair("health", "test-client").accessToken
        val response = client.post("/mcp") {
            header("Authorization", "Bearer $bearer")
            contentType(ContentType.Application.Json)
            setBody("""{"jsonrpc":"2.0","method":"initialize","id":1}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `register endpoint also requires token`() = testApplication {
        val registry = InMemoryProviderRegistry()
        registry.register("health", stubProvider())
        registry.setEnabled("health", true)
        val tokenStore = InMemoryTokenStore()
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                hostname = "test.rousecontext.com",
                integration = "health",
                internalToken = validToken
            )
        }

        val response = client.post("/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"client_name":"foo"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `null internalToken leaves guard disabled for backward-compat tests`() = testApplication {
        // Some tests don't care about the internal token; when `internalToken` is
        // null, the guard should NOT be installed and requests proceed as before.
        val registry = InMemoryProviderRegistry()
        registry.register("health", stubProvider())
        registry.setEnabled("health", true)
        val tokenStore = InMemoryTokenStore()
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                hostname = "test.rousecontext.com",
                integration = "health",
                internalToken = null
            )
        }

        val response = client.get("/.well-known/oauth-authorization-server")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `McpSession generates internal token on start and rotates on restart`() = runBlocking {
        val registry = InMemoryProviderRegistry()
        val tokenStore = InMemoryTokenStore()
        val session = McpSession(
            registry = registry,
            tokenStore = tokenStore,
            hostname = "test.rousecontext.com",
            integration = "health"
        )

        try {
            session.start(port = 0)
            val firstToken = session.internalToken
            assertTrue(
                "Internal token must be non-empty",
                firstToken.isNotEmpty()
            )
            // Base64url-encoded 32 bytes -> 43 chars (no padding).
            assertTrue(
                "Expected a 43-character base64url token, was ${firstToken.length}",
                firstToken.length >= 32
            )
            session.stop()

            session.start(port = 0)
            val secondToken = session.internalToken
            assertNotEquals(
                "Internal token must rotate on restart; see #177",
                firstToken,
                secondToken
            )
        } finally {
            session.stop()
        }
    }

    @Test
    fun `generateInternalToken returns unique values`() {
        val a = generateInternalToken()
        val b = generateInternalToken()
        assertNotEquals(a, b)
        assertTrue("Token should be non-empty", a.isNotEmpty())
    }
}
