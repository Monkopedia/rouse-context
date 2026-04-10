package com.rousecontext.mcp.core

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpRoutingTest {

    private fun stubProvider(id: String, displayName: String): McpServerProvider {
        return object : McpServerProvider {
            override val id = id
            override val displayName = displayName
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
    fun `oauth metadata returns valid RFC 8414 for enabled integration`() = testApplication {
        val registry = testRegistry("health" to stubProvider("health", "Health Connect"))
        val tokenStore = InMemoryTokenStore()
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                hostname = "brave-falcon.rousecontext.com",
                integration = "health"
            )
        }

        val response = client.get("/.well-known/oauth-authorization-server")
        assertEquals(HttpStatusCode.OK, response.status)

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(
            "https://brave-falcon.rousecontext.com",
            json["issuer"]?.jsonPrimitive?.content
        )
        assertEquals(
            "https://brave-falcon.rousecontext.com/device/authorize",
            json["device_authorization_endpoint"]?.jsonPrimitive?.content
        )
        assertEquals(
            "https://brave-falcon.rousecontext.com/token",
            json["token_endpoint"]?.jsonPrimitive?.content
        )
        val grantTypes = json["grant_types_supported"]?.jsonArray
            ?.map { it.jsonPrimitive.content }
        assertTrue(grantTypes!!.contains("urn:ietf:params:oauth:grant-type:device_code"))
    }

    @Test
    fun `unknown path returns 404`() = testApplication {
        val registry = testRegistry("health" to stubProvider("health", "Health Connect"))
        val tokenStore = InMemoryTokenStore()
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                hostname = "brave-falcon.rousecontext.com",
                integration = "health"
            )
        }

        val response = client.get("/.well-known/oauth-authorization-server") {
            header("Host", "brave-unknown.abc123.rousecontext.com")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `disabled integration returns 404`() = testApplication {
        val registry = InMemoryProviderRegistry()
        registry.register("health", stubProvider("health", "Health Connect"))
        registry.setEnabled("health", false)
        val tokenStore = InMemoryTokenStore()
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                hostname = "brave-falcon.rousecontext.com",
                integration = "health"
            )
        }

        val response = client.get("/.well-known/oauth-authorization-server")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
