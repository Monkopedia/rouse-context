package com.rousecontext.app

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlBuilderTest {

    @Test
    fun `URL includes secret prefix when present`() {
        val url = buildMcpUrl(
            secretPrefix = "brave-falcon",
            subdomain = "abc123",
            baseDomain = "rousecontext.com",
            integrationPath = "/health"
        )
        assertEquals("https://brave-falcon.abc123.rousecontext.com/health/mcp", url)
    }

    @Test
    fun `URL omits secret prefix when null`() {
        val url = buildMcpUrl(
            secretPrefix = null,
            subdomain = "abc123",
            baseDomain = "rousecontext.com",
            integrationPath = "/health"
        )
        assertEquals("https://abc123.rousecontext.com/health/mcp", url)
    }

    @Test
    fun `URL works with different integration paths`() {
        val url = buildMcpUrl(
            secretPrefix = "swift-tiger",
            subdomain = "xyz789",
            baseDomain = "rousecontext.com",
            integrationPath = "/notifications"
        )
        assertEquals(
            "https://swift-tiger.xyz789.rousecontext.com/notifications/mcp",
            url
        )
    }
}
