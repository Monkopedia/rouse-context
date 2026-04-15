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

    /**
     * Regression guard for #164: when onboarding has assigned a subdomain but no
     * secret has been provisioned yet for the requested integration, buildUrl
     * must return null (so the UI can render a diagnostic placeholder instead
     * of an empty card).
     */
    @Test
    fun `buildUrl returns null then real URL once secret is stored`() = runBlocking {
        val store = SubdomainAndSecretsStore(subdomain = "abc123")
        val provider = McpUrlProvider(store, "rousecontext.com")

        assertNull(
            "buildUrl must be null when no secret is stored for the integration",
            provider.buildUrl("health")
        )

        store.secrets = mapOf("health" to "brave-health")

        assertEquals(
            "buildUrl must resolve once a secret exists",
            "https://brave-health.abc123.rousecontext.com/mcp",
            provider.buildUrl("health")
        )
    }
}

/**
 * Minimal in-file [CertificateStore] fake for #164 regression tests.
 * Only the two methods buildUrl touches are meaningful; everything else
 * is a safe default so the test stays readable.
 */
private class SubdomainAndSecretsStore(
    private val subdomain: String?,
    var secrets: Map<String, String> = emptyMap()
) : CertificateStore {
    override suspend fun getSubdomain(): String? = subdomain
    override suspend fun getIntegrationSecrets(): Map<String, String>? =
        if (secrets.isEmpty()) null else secrets

    override suspend fun getCertChain(): List<ByteArray>? = null
    override suspend fun getPrivateKeyBytes(): ByteArray? = null
    override suspend fun storeCertChain(chain: List<ByteArray>) = Unit
    override suspend fun getCertExpiry(): Long? = null
    override suspend fun getKnownFingerprints(): Set<String> = emptySet()
    override suspend fun storeFingerprint(fingerprint: String) = Unit
    override suspend fun storeCertificate(pemChain: String) = Unit
    override suspend fun getCertificate(): String? = null
    override suspend fun storeClientCertificate(pemChain: String) = Unit
    override suspend fun getClientCertificate(): String? = null
    override suspend fun storeRelayCaCert(pem: String) = Unit
    override suspend fun getRelayCaCert(): String? = null
    override suspend fun storeSubdomain(subdomain: String) = Unit
    override suspend fun storeIntegrationSecrets(secrets: Map<String, String>) {
        this.secrets = secrets
    }
    override suspend fun storePrivateKey(pemKey: String) = Unit
    override suspend fun getPrivateKey(): String? = null
    override suspend fun clear() {
        secrets = emptyMap()
    }
    override suspend fun clearCertificates() = Unit
}
