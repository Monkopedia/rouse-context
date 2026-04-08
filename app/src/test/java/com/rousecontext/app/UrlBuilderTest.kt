package com.rousecontext.app

import com.rousecontext.tunnel.CertificateStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlBuilderTest {

    @Test
    fun `buildMcpUrl produces correct URL`() {
        val url = buildMcpUrl(
            integrationSecret = "brave-health",
            subdomain = "abc123",
            baseDomain = "rousecontext.com"
        )
        assertEquals("https://brave-health.abc123.rousecontext.com/mcp", url)
    }

    @Test
    fun `buildMcpUrl works with different integrations`() {
        val url = buildMcpUrl(
            integrationSecret = "swift-notifications",
            subdomain = "xyz789",
            baseDomain = "rousecontext.com"
        )
        assertEquals(
            "https://swift-notifications.xyz789.rousecontext.com/mcp",
            url
        )
    }

    @Test
    fun `McpUrlProvider buildUrl returns correct URL`() = runBlocking {
        val certStore = mockk<CertificateStore> {
            coEvery { getSubdomain() } returns "abc123"
            coEvery { getSecretForIntegration("health") } returns "brave-health"
            coEvery { getSecretForIntegration("notifications") } returns "swift-notifications"
        }
        val provider = McpUrlProvider(certStore, "rousecontext.com")

        assertEquals(
            "https://brave-health.abc123.rousecontext.com/mcp",
            provider.buildUrl("health")
        )
        assertEquals(
            "https://swift-notifications.abc123.rousecontext.com/mcp",
            provider.buildUrl("notifications")
        )
    }

    @Test
    fun `McpUrlProvider buildHostname returns correct hostname`() = runBlocking {
        val certStore = mockk<CertificateStore> {
            coEvery { getSubdomain() } returns "abc123"
            coEvery { getSecretForIntegration("health") } returns "brave-health"
        }
        val provider = McpUrlProvider(certStore, "rousecontext.com")

        assertEquals(
            "brave-health.abc123.rousecontext.com",
            provider.buildHostname("health")
        )
    }

    @Test
    fun `McpUrlProvider returns null when subdomain missing`() = runBlocking {
        val certStore = mockk<CertificateStore> {
            coEvery { getSubdomain() } returns null
            coEvery { getSecretForIntegration(any()) } returns "brave-health"
        }
        val provider = McpUrlProvider(certStore, "rousecontext.com")

        assertNull(provider.buildUrl("health"))
        assertNull(provider.buildHostname("health"))
    }

    @Test
    fun `McpUrlProvider returns null when integration secret missing`() = runBlocking {
        val certStore = mockk<CertificateStore> {
            coEvery { getSubdomain() } returns "abc123"
            coEvery { getSecretForIntegration("health") } returns null
        }
        val provider = McpUrlProvider(certStore, "rousecontext.com")

        assertNull(provider.buildUrl("health"))
    }
}
