package com.rousecontext.app.state

import com.rousecontext.api.McpIntegration
import com.rousecontext.mcp.core.McpServerProvider
import com.rousecontext.tunnel.CertificateStore
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [OAuthHostnameProvider]. Exercises the subdomain/secret
 * resolution paths without any Android framework dependencies.
 */
class OAuthHostnameProviderTest {

    @Test
    fun `resolve returns localhost when subdomain is null`() = runBlocking {
        val store = FakeStore(subdomain = null, secrets = mapOf("health" to "happy"))
        val provider = OAuthHostnameProvider(
            certStore = store,
            baseDomain = "rousecontext.com",
            integrations = listOf(fakeIntegration("health"))
        )

        assertEquals("localhost", provider.resolve())
    }

    @Test
    fun `resolve returns localhost when secret is missing`() = runBlocking {
        val store = FakeStore(subdomain = "abc123", secrets = emptyMap())
        val provider = OAuthHostnameProvider(
            certStore = store,
            baseDomain = "rousecontext.com",
            integrations = listOf(fakeIntegration("health"))
        )

        assertEquals("localhost", provider.resolve())
    }

    @Test
    fun `resolve joins secret subdomain and base domain`() = runBlocking {
        val store = FakeStore(
            subdomain = "abc123",
            secrets = mapOf("health" to "happy")
        )
        val provider = OAuthHostnameProvider(
            certStore = store,
            baseDomain = "rousecontext.com",
            integrations = listOf(fakeIntegration("health"))
        )

        assertEquals("happy.abc123.rousecontext.com", provider.resolve())
    }

    @Test
    fun `resolve uses first integration id`() = runBlocking {
        val store = FakeStore(
            subdomain = "device1",
            secrets = mapOf("health" to "apple", "usage" to "banana")
        )
        val provider = OAuthHostnameProvider(
            certStore = store,
            baseDomain = "example.org",
            // First integration is usage — provider reads that id's secret.
            integrations = listOf(fakeIntegration("usage"), fakeIntegration("health"))
        )

        assertEquals("banana.device1.example.org", provider.resolve())
    }

    @Test
    fun `resolve defaults to health id when integrations list empty`() = runBlocking {
        val store = FakeStore(
            subdomain = "device1",
            secrets = mapOf("health" to "orange")
        )
        val provider = OAuthHostnameProvider(
            certStore = store,
            baseDomain = "example.org",
            integrations = emptyList()
        )

        assertEquals("orange.device1.example.org", provider.resolve())
    }

    private fun fakeIntegration(integrationId: String): McpIntegration = object : McpIntegration {
        override val id = integrationId
        override val displayName = integrationId
        override val description = ""
        override val path = "/$integrationId"
        override val onboardingRoute = "setup"
        override val settingsRoute = "settings"
        override val provider: McpServerProvider = object : McpServerProvider {
            override val id = integrationId
            override val displayName = integrationId
            override fun register(server: Server) = Unit
        }

        override suspend fun isAvailable(): Boolean = true
    }

    private class FakeStore(
        private val subdomain: String?,
        private val secrets: Map<String, String>
    ) : CertificateStore {
        override suspend fun getSubdomain(): String? = subdomain
        override suspend fun getIntegrationSecrets(): Map<String, String>? =
            if (secrets.isEmpty()) null else secrets
        override suspend fun storeIntegrationSecrets(secrets: Map<String, String>) = Unit
        override suspend fun getCertChain(): List<ByteArray>? = null
        override suspend fun getPrivateKeyBytes(): ByteArray? = null
        override suspend fun storeCertChain(chain: List<ByteArray>) = Unit
        override suspend fun getCertExpiry(): Long? = null
        override suspend fun getKnownFingerprints(): Set<String> = emptySet()
        override suspend fun storeFingerprint(fingerprint: String) = Unit
        override suspend fun hasFingerprintBootstrapMarker(): Boolean = false
        override suspend fun writeFingerprintBootstrapMarker() = Unit
        override suspend fun storeCertificate(pemChain: String) = Unit
        override suspend fun getCertificate(): String? = null
        override suspend fun storeClientCertificate(pemChain: String) = Unit
        override suspend fun getClientCertificate(): String? = null
        override suspend fun storeRelayCaCert(pem: String) = Unit
        override suspend fun getRelayCaCert(): String? = null
        override suspend fun storeSubdomain(subdomain: String) = Unit
        override suspend fun clear() = Unit
        override suspend fun clearCertificates() = Unit
    }
}
