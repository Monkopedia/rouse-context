package com.rousecontext.mcp.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Regression tests for issue #127: the local MCP HTTP server MUST bind to
 * the IPv4 loopback interface only. Binding to 0.0.0.0 (Ktor's default when
 * no host is provided) would expose OAuth discovery and device-code endpoints
 * to any host sharing the device's LAN.
 */
class McpSessionBindTest {

    @Test
    fun `started session is bound to 127_0_0_1 only`() = runBlocking {
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
            val boundHost = session.resolveHost()

            assertEquals(
                "McpSession must bind to the IPv4 loopback interface; see #127",
                "127.0.0.1",
                boundHost
            )
            assertNotEquals(
                "McpSession must not bind to 0.0.0.0 (all interfaces); see #127",
                "0.0.0.0",
                boundHost
            )
        } finally {
            session.stop()
        }
    }
}
