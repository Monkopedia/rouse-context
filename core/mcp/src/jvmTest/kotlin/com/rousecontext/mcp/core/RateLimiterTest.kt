package com.rousecontext.mcp.core

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.server.Server
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {

    // -- Unit tests for RateLimiter --

    @Test
    fun `allows requests within limit`() {
        val clock = FakeClock()
        val limiter = RateLimiter(maxRequests = 5, windowMs = 60_000L, clock = clock)

        repeat(5) {
            assertTrue("Request ${it + 1} should be allowed", limiter.tryAcquire("register"))
        }
    }

    @Test
    fun `rejects requests over limit`() {
        val clock = FakeClock()
        val limiter = RateLimiter(maxRequests = 3, windowMs = 60_000L, clock = clock)

        repeat(3) { limiter.tryAcquire("register") }
        val allowed = limiter.tryAcquire("register")
        assertTrue("Request over limit should be rejected", !allowed)
    }

    @Test
    fun `resets after window expires`() {
        val clock = FakeClock()
        val limiter = RateLimiter(maxRequests = 2, windowMs = 60_000L, clock = clock)

        repeat(2) { limiter.tryAcquire("register") }
        assertTrue("Should be at limit", !limiter.tryAcquire("register"))

        clock.advanceSeconds(61)
        assertTrue("Should allow after window reset", limiter.tryAcquire("register"))
    }

    @Test
    fun `tracks endpoints independently`() {
        val clock = FakeClock()
        val limiter = RateLimiter(maxRequests = 2, windowMs = 60_000L, clock = clock)

        repeat(2) { limiter.tryAcquire("register") }
        assertTrue("Register should be at limit", !limiter.tryAcquire("register"))
        assertTrue("Token should still be allowed", limiter.tryAcquire("token"))
    }

    // -- Integration tests with Ktor --

    private fun stubProvider(): McpServerProvider = object : McpServerProvider {
        override val id = "health"
        override val displayName = "Health Connect"
        override fun register(server: Server) = Unit
    }

    @Test
    fun `register endpoint returns 429 when rate limited`() = testApplication {
        val registry = InMemoryProviderRegistry()
        registry.register("health", stubProvider())
        registry.setEnabled("health", true)
        val tokenStore = InMemoryTokenStore()
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)
        val clock = FakeClock()
        val rateLimiter = RateLimiter(maxRequests = 2, windowMs = 60_000L, clock = clock)

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                hostname = "test.rousecontext.com",
                integration = "health",
                clock = clock,
                rateLimiter = rateLimiter
            )
        }

        // First two requests should succeed
        repeat(2) {
            val response = client.post("/register") {
                contentType(ContentType.Application.Json)
                setBody("""{"client_name":"test"}""")
            }
            assertTrue(
                "Request ${it + 1} should not be 429, was ${response.status}",
                response.status != HttpStatusCode.TooManyRequests
            )
        }

        // Third request should be rate limited
        val response = client.post("/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"client_name":"test"}""")
        }
        assertEquals(HttpStatusCode.TooManyRequests, response.status)
    }

    @Test
    fun `authorize endpoint returns 429 when rate limited`() = testApplication {
        val registry = InMemoryProviderRegistry()
        registry.register("health", stubProvider())
        registry.setEnabled("health", true)
        val tokenStore = InMemoryTokenStore()
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)
        val clock = FakeClock()
        val rateLimiter = RateLimiter(maxRequests = 1, windowMs = 60_000L, clock = clock)

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                hostname = "test.rousecontext.com",
                integration = "health",
                clock = clock,
                rateLimiter = rateLimiter
            )
        }

        // First request should succeed (returns 400 because missing params, but not 429)
        val first = client.get("/authorize")
        assertTrue(first.status != HttpStatusCode.TooManyRequests)

        // Second request should be rate limited
        val second = client.get("/authorize")
        assertEquals(HttpStatusCode.TooManyRequests, second.status)
    }

    @Test
    fun `device authorize endpoint returns 429 when rate limited`() = testApplication {
        val registry = InMemoryProviderRegistry()
        registry.register("health", stubProvider())
        registry.setEnabled("health", true)
        val tokenStore = InMemoryTokenStore()
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)
        val clock = FakeClock()
        val rateLimiter = RateLimiter(maxRequests = 2, windowMs = 60_000L, clock = clock)

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                hostname = "test.rousecontext.com",
                integration = "health",
                clock = clock,
                rateLimiter = rateLimiter
            )
        }

        // First two requests should succeed (not 429)
        repeat(2) {
            val response = client.post("/device/authorize")
            assertTrue(
                "Request ${it + 1} should not be 429, was ${response.status}",
                response.status != HttpStatusCode.TooManyRequests
            )
        }

        // Third request should be rate limited
        val response = client.post("/device/authorize")
        assertEquals(HttpStatusCode.TooManyRequests, response.status)
    }

    @Test
    fun `token endpoint is not rate limited`() = testApplication {
        // Token exchange is legitimately polled many times during device-code flow,
        // so the rate limiter must NOT apply to /token even when one is configured.
        val registry = InMemoryProviderRegistry()
        registry.register("health", stubProvider())
        registry.setEnabled("health", true)
        val tokenStore = InMemoryTokenStore()
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)
        val clock = FakeClock()
        val rateLimiter = RateLimiter(maxRequests = 1, windowMs = 60_000L, clock = clock)

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                hostname = "test.rousecontext.com",
                integration = "health",
                clock = clock,
                rateLimiter = rateLimiter
            )
        }

        // Hit the device-code poll endpoint well beyond the rate limit.
        // Each returns an OAuth error (invalid_grant / authorization_pending),
        // but NONE should ever return 429.
        repeat(POLL_REQUEST_COUNT) { i ->
            val response = client.post("/token") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"grant_type":"urn:ietf:params:oauth:grant-type:device_code",""" +
                        """"device_code":"nonexistent"}"""
                )
            }
            assertTrue(
                "Poll request ${i + 1} should not be 429, was ${response.status}",
                response.status != HttpStatusCode.TooManyRequests
            )
        }
    }

    @Test
    fun `authorize rate limit window resets after elapsed time`() = testApplication {
        val registry = InMemoryProviderRegistry()
        registry.register("health", stubProvider())
        registry.setEnabled("health", true)
        val tokenStore = InMemoryTokenStore()
        val deviceCodeManager = DeviceCodeManager(tokenStore = tokenStore)
        val clock = FakeClock()
        val rateLimiter = RateLimiter(maxRequests = 5, windowMs = 60_000L, clock = clock)

        application {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                hostname = "test.rousecontext.com",
                integration = "health",
                clock = clock,
                rateLimiter = rateLimiter
            )
        }

        // Exhaust the window.
        repeat(5) {
            val response = client.get("/authorize")
            assertTrue(
                "Request ${it + 1} should not be 429, was ${response.status}",
                response.status != HttpStatusCode.TooManyRequests
            )
        }
        val blocked = client.get("/authorize")
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status)

        // Advance past the window and confirm requests are allowed again.
        clock.advanceSeconds(61)
        val afterReset = client.get("/authorize")
        assertTrue(
            "After window reset the request should not be 429, was ${afterReset.status}",
            afterReset.status != HttpStatusCode.TooManyRequests
        )
    }

    @Test
    fun `default McpSession rate limiter blocks authorize after threshold`() = testApplication {
        // Verifies the McpSession-level default is applied when no explicit limiter
        // is configured, matching production wiring from AppModule.
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
                // Use the defaults McpSession supplies in production.
                rateLimiter = McpSession.defaultOAuthInitRateLimiter()
            )
        }

        // 5 requests within the default window are allowed.
        repeat(OAUTH_INIT_DEFAULT_LIMIT) {
            val response = client.get("/authorize")
            assertTrue(
                "Request ${it + 1} should not be 429, was ${response.status}",
                response.status != HttpStatusCode.TooManyRequests
            )
        }
        // The 6th is blocked.
        val blocked = client.get("/authorize")
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
    }

    companion object {
        private const val POLL_REQUEST_COUNT = 12
        private const val OAUTH_INIT_DEFAULT_LIMIT = 5
    }
}
