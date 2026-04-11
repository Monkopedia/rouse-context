package com.rousecontext.mcp.core

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.server.Server
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that the /authorize endpoint returns correct Content-Security-Policy
 * headers that allow the page's inline styles, inline scripts, and data: images
 * to function.
 *
 * Without these CSP directives, browsers block the inline CSS/JS on the
 * authorization page, rendering it unstyled and non-functional.
 */
class AuthPageCspTest {

    private val validChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
    private val defaultRedirectUri = "http://localhost:3000/callback"

    private fun stubProvider(): McpServerProvider = object : McpServerProvider {
        override val id = "health"
        override val displayName = "Health Connect"
        override fun register(server: Server) = Unit
    }

    private fun parseCspDirectives(csp: String): Map<String, String> = csp.split(";")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .associate { directive ->
            val parts = directive.split(" ", limit = 2)
            parts[0] to (parts.getOrNull(1) ?: "")
        }

    @Test
    fun `authorize page returns CSP header allowing inline styles`() = testApplication {
        val registry = InMemoryProviderRegistry()
        registry.register("health", stubProvider())
        registry.setEnabled("health", true)
        val tokenStore = InMemoryTokenStore()
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)
        val authorizationCodeManager = AuthorizationCodeManager(tokenStore = tokenStore)
        authorizationCodeManager.registerClient(
            "test-client",
            "Test App",
            listOf(defaultRedirectUri)
        )

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                authorizationCodeManager = authorizationCodeManager,
                hostname = "test.rousecontext.com",
                integration = "health"
            )
        }

        val response = client.get(
            "/authorize?" +
                "response_type=code&" +
                "client_id=test-client&" +
                "code_challenge=$validChallenge&" +
                "code_challenge_method=S256&" +
                "redirect_uri=$defaultRedirectUri&" +
                "state=test-state"
        )

        assertEquals(HttpStatusCode.OK, response.status)

        val csp = response.headers["Content-Security-Policy"]
        assertNotNull("Expected Content-Security-Policy header", csp)

        val directives = parseCspDirectives(csp!!)

        // Verify inline styles are allowed
        val styleSrc = directives["style-src"]
        assertNotNull("Expected style-src directive", styleSrc)
        assertTrue(
            "style-src should contain 'unsafe-inline', got: $styleSrc",
            styleSrc!!.contains("'unsafe-inline'")
        )

        // Verify inline scripts are allowed
        val scriptSrc = directives["script-src"]
        assertNotNull("Expected script-src directive", scriptSrc)
        assertTrue(
            "script-src should contain 'unsafe-inline', got: $scriptSrc",
            scriptSrc!!.contains("'unsafe-inline'")
        )

        // Verify data: images are allowed
        val imgSrc = directives["img-src"]
        assertNotNull("Expected img-src directive", imgSrc)
        assertTrue(
            "img-src should contain 'data:', got: $imgSrc",
            imgSrc!!.contains("data:")
        )

        // Verify frame-ancestors is 'none' (clickjacking protection)
        val frameAncestors = directives["frame-ancestors"]
        assertNotNull("Expected frame-ancestors directive", frameAncestors)
        assertTrue(
            "frame-ancestors should be 'none', got: $frameAncestors",
            frameAncestors!!.contains("'none'")
        )
    }

    @Test
    fun `authorize page returns X-Frame-Options DENY`() = testApplication {
        val registry = InMemoryProviderRegistry()
        registry.register("health", stubProvider())
        registry.setEnabled("health", true)
        val tokenStore = InMemoryTokenStore()
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)
        val authorizationCodeManager = AuthorizationCodeManager(tokenStore = tokenStore)
        authorizationCodeManager.registerClient(
            "test-client",
            "Test App",
            listOf(defaultRedirectUri)
        )

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                authorizationCodeManager = authorizationCodeManager,
                hostname = "test.rousecontext.com",
                integration = "health"
            )
        }

        val response = client.get(
            "/authorize?" +
                "response_type=code&" +
                "client_id=test-client&" +
                "code_challenge=$validChallenge&" +
                "code_challenge_method=S256&" +
                "redirect_uri=$defaultRedirectUri&" +
                "state=test-state"
        )

        assertEquals(HttpStatusCode.OK, response.status)

        val xFrame = response.headers["X-Frame-Options"]
        assertEquals("DENY", xFrame)
    }

    @Test
    fun `authorize page returns Strict-Transport-Security`() = testApplication {
        val registry = InMemoryProviderRegistry()
        registry.register("health", stubProvider())
        registry.setEnabled("health", true)
        val tokenStore = InMemoryTokenStore()
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)
        val authorizationCodeManager = AuthorizationCodeManager(tokenStore = tokenStore)
        authorizationCodeManager.registerClient(
            "test-client",
            "Test App",
            listOf(defaultRedirectUri)
        )

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                authorizationCodeManager = authorizationCodeManager,
                hostname = "test.rousecontext.com",
                integration = "health"
            )
        }

        val response = client.get(
            "/authorize?" +
                "response_type=code&" +
                "client_id=test-client&" +
                "code_challenge=$validChallenge&" +
                "code_challenge_method=S256&" +
                "redirect_uri=$defaultRedirectUri&" +
                "state=test-state"
        )

        assertEquals(HttpStatusCode.OK, response.status)

        val hsts = response.headers["Strict-Transport-Security"]
        assertNotNull("Expected Strict-Transport-Security header", hsts)
        assertTrue(
            "HSTS should contain max-age, got: $hsts",
            hsts!!.contains("max-age=")
        )
    }

    @Test
    fun `authorize page returns HTML content`() = testApplication {
        val registry = InMemoryProviderRegistry()
        registry.register("health", stubProvider())
        registry.setEnabled("health", true)
        val tokenStore = InMemoryTokenStore()
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)
        val authorizationCodeManager = AuthorizationCodeManager(tokenStore = tokenStore)
        authorizationCodeManager.registerClient(
            "test-client",
            "Test App",
            listOf(defaultRedirectUri)
        )

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                authorizationCodeManager = authorizationCodeManager,
                hostname = "test.rousecontext.com",
                integration = "health"
            )
        }

        val response = client.get(
            "/authorize?" +
                "response_type=code&" +
                "client_id=test-client&" +
                "code_challenge=$validChallenge&" +
                "code_challenge_method=S256&" +
                "redirect_uri=$defaultRedirectUri&" +
                "state=test-state"
        )

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue("Response should be HTML", body.contains("<!DOCTYPE html>"))
        assertTrue("Response should contain display code", body.contains("Rouse Context"))
    }
}
