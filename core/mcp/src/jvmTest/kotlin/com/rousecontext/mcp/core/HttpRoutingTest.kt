package com.rousecontext.mcp.core

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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

    private fun stubProvider(id: String, displayName: String): McpServerProvider =
        object : McpServerProvider {
            override val id = id
            override val displayName = displayName
            override fun register(server: Server) = Unit
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

    @Test
    fun `security alert blocks register with 503`() = testApplication {
        val registry = testRegistry("health" to stubProvider("health", "Health Connect"))
        val tokenStore = InMemoryTokenStore()
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                hostname = "brave-falcon.rousecontext.com",
                integration = "health",
                securityAlertCheck = { true }
            )
        }

        val response = client.post("/register") {
            header("Content-Type", "application/json")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("security_alert", json["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `security alert blocks token with 503`() = testApplication {
        val registry = testRegistry("health" to stubProvider("health", "Health Connect"))
        val tokenStore = InMemoryTokenStore()
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                hostname = "brave-falcon.rousecontext.com",
                integration = "health",
                securityAlertCheck = { true }
            )
        }

        val response = client.post("/token") {
            header("Content-Type", "application/json")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("security_alert", json["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `security alert blocks mcp with 503`() = testApplication {
        val registry = testRegistry("health" to stubProvider("health", "Health Connect"))
        val tokenStore = InMemoryTokenStore()
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                hostname = "brave-falcon.rousecontext.com",
                integration = "health",
                securityAlertCheck = { true }
            )
        }

        val response = client.post("/mcp") {
            header("Content-Type", "application/json")
            header("Authorization", "Bearer test-token")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("security_alert", json["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `security alert blocks authorize with 503`() = testApplication {
        val registry = testRegistry("health" to stubProvider("health", "Health Connect"))
        val tokenStore = InMemoryTokenStore()
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                hostname = "brave-falcon.rousecontext.com",
                integration = "health",
                securityAlertCheck = { true }
            )
        }

        val response = client.get("/authorize?response_type=code&client_id=x")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("security_alert", json["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `no security alert allows normal request flow`() = testApplication {
        val registry = testRegistry("health" to stubProvider("health", "Health Connect"))
        val tokenStore = InMemoryTokenStore()
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                hostname = "brave-falcon.rousecontext.com",
                integration = "health",
                securityAlertCheck = { false }
            )
        }

        // OAuth metadata should work fine when alert is false
        val response = client.get("/.well-known/oauth-authorization-server")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `malformed register body invokes log lambda`() = testApplication {
        val registry = testRegistry("health" to stubProvider("health", "Health Connect"))
        val tokenStore = InMemoryTokenStore()
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)
        val captured = mutableListOf<Pair<LogLevel, String>>()

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                hostname = "brave-falcon.rousecontext.com",
                integration = "health",
                log = { level, msg -> captured.add(level to msg) }
            )
        }

        val response = client.post("/register") {
            header("Content-Type", "application/json")
            setBody("not valid json {{{")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(
            "Expected /register body read failure log, got $captured",
            captured.any {
                it.first == LogLevel.WARN &&
                    it.second.startsWith("McpRouting: /register body read failed:")
            }
        )
    }
}
