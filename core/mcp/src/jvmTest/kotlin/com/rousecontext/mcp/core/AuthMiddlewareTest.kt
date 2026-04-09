package com.rousecontext.mcp.core

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.server.Server
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthMiddlewareTest {

    private fun stubProvider(id: String): McpServerProvider {
        return object : McpServerProvider {
            override val id = id
            override val displayName = id
            override fun register(server: Server) = Unit
        }
    }

    private fun testRegistry(
        vararg enabled: Pair<String, McpServerProvider>
    ): InMemoryProviderRegistry {
        val registry = InMemoryProviderRegistry()
        for ((path, provider) in enabled) {
            registry.register(path, provider)
            registry.setEnabled(path, true)
        }
        return registry
    }

    @Test
    fun `request without Bearer returns 401 with WWW-Authenticate`() = testApplication {
        val registry = testRegistry("health" to stubProvider("health"))
        val tokenStore = InMemoryTokenStore()
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                hostname = "test.rousecontext.com",
                integration = "health"
            )
        }

        val response = client.post("/health/mcp")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val wwwAuth = response.headers["WWW-Authenticate"]
        assertTrue(wwwAuth != null && wwwAuth.contains("Bearer"))
        assertTrue(
            wwwAuth != null && wwwAuth.contains(
                "https://test.rousecontext.com/health/.well-known/oauth-authorization-server"
            )
        )
    }

    @Test
    fun `valid token passes through`() = testApplication {
        val registry = testRegistry("health" to stubProvider("health"))
        val tokenStore = InMemoryTokenStore()
        val token = tokenStore.createTokenPair("health", "client-1").accessToken
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                hostname = "test.rousecontext.com",
                integration = "health"
            )
        }

        // MCP endpoint with valid token should not return 401
        val response = client.post("/health/mcp") {
            header("Authorization", "Bearer $token")
        }
        // It won't return a full MCP response in this test setup,
        // but it should NOT be 401
        assertTrue(response.status != HttpStatusCode.Unauthorized)
    }

    @Test
    fun `revoked token returns 401`() = testApplication {
        val registry = testRegistry("health" to stubProvider("health"))
        val tokenStore = InMemoryTokenStore()
        val token = tokenStore.createTokenPair("health", "client-1").accessToken
        tokenStore.revokeToken("health", token)
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                hostname = "test.rousecontext.com",
                integration = "health"
            )
        }

        val response = client.post("/health/mcp") {
            header("Authorization", "Bearer $token")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `wrong-integration token returns 401`() = testApplication {
        val registry = testRegistry(
            "health" to stubProvider("health"),
            "notifications" to stubProvider("notifications")
        )
        val tokenStore = InMemoryTokenStore()
        // Token for notifications, not health
        val token = tokenStore.createTokenPair("notifications", "client-1").accessToken
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                hostname = "test.rousecontext.com",
                integration = "health"
            )
        }

        val response = client.post("/health/mcp") {
            header("Authorization", "Bearer $token")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `OAuth endpoints accessible without Bearer`() = testApplication {
        val registry = testRegistry("health" to stubProvider("health"))
        val tokenStore = InMemoryTokenStore()
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                hostname = "test.rousecontext.com",
                integration = "health"
            )
        }

        // OAuth metadata - no auth required
        val metadataResp = client.get("/health/.well-known/oauth-authorization-server")
        assertEquals(HttpStatusCode.OK, metadataResp.status)

        // Device authorize - no auth required
        val authorizeResp = client.post("/health/device/authorize")
        assertEquals(HttpStatusCode.OK, authorizeResp.status)

        // Token endpoint - no auth required (it checks device_code instead)
        val tokenResp = client.post("/health/token")
        // May return 400 (bad request due to missing params) but NOT 401
        assertTrue(tokenResp.status != HttpStatusCode.Unauthorized)
    }
}
