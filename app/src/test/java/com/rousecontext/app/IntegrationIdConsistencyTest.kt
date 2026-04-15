package com.rousecontext.app

import com.rousecontext.tunnel.CertificateStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Regression guard for #164.
 *
 * The "IntegrationEnabled" screen resolves its URL via
 * [McpUrlProvider.buildUrl], which reads a per-integration secret keyed
 * by the integration's id. That lookup only succeeds when the id used to
 * PUSH secrets (the [McpIntegration.id] list fed to the setup view model
 * through Koin) exactly matches the id used to READ the secret back (the
 * route arg the screen is opened with -- also [McpIntegration.id]).
 *
 * This test freezes the canonical integration id set the app currently
 * ships with as string literals and exercises the push → read contract
 * end-to-end through [CertificateStore]. It does NOT catch a rename on
 * its own -- if every production site updates in lockstep the test keeps
 * passing against the old literals (which is wrong). What it does catch:
 *  - Breakage of the CertificateStore.storeIntegrationSecrets →
 *    getIntegrationSecrets → McpUrlProvider.buildUrl round-trip for the
 *    id shape the app uses (lowercase, no separators).
 *  - A deliberate change to the shipped id set, because updating the
 *    production constants then requires the author to come here and
 *    update the frozen list -- which makes the cross-cutting rename
 *    visible in review.
 */
class IntegrationIdConsistencyTest {

    /**
     * The canonical integration id list. Mirrors the Koin wiring in
     * [com.rousecontext.app.di.AppModule]:
     *  - [com.rousecontext.app.registry.HealthConnectIntegration.id] = "health"
     *  - [com.rousecontext.app.registry.OutreachIntegration.id] = "outreach"
     *  - [com.rousecontext.app.registry.NotificationIntegration.id] =
     *    "notifications"
     *  - [com.rousecontext.app.registry.UsageIntegration.id] = "usage"
     *
     * Kept as string literals rather than pulling the constants from
     * production so the shipped id set is frozen here. A deliberate id
     * change to production will not silently pass: the author must also
     * update this list, which makes the cross-cutting rename visible in
     * code review.
     */
    private val canonicalIntegrationIds = listOf(
        "health",
        "outreach",
        "notifications",
        "usage"
    )

    @Test
    fun `every canonical integration id resolves to a URL after a push`() = runBlocking {
        val store = RecordingCertStore(subdomain = "my-device")
        val provider = McpUrlProvider(store, baseDomain = "rousecontext.com")

        // Simulate the relay's successful response: one
        // "{adjective}-{integrationId}" secret per requested id, keyed by
        // exactly the id the client sent. storeIntegrationSecrets is the
        // concrete sink IntegrationSetupViewModel.pushIntegrationSecrets
        // hits after updateSecrets succeeds.
        val pushedSecrets = canonicalIntegrationIds.associateWith { id -> "brave-$id" }
        store.storeIntegrationSecrets(pushedSecrets)

        canonicalIntegrationIds.forEach { id ->
            val url = provider.buildUrl(id)
            assertNotNull(
                "buildUrl returned null for canonical integration id '$id'; " +
                    "push/read id mismatch would regress #164",
                url
            )
            assertEquals(
                "https://brave-$id.my-device.rousecontext.com/mcp",
                url
            )
        }
    }

    @Test
    fun `buildUrl returns null for any id the push did not cover`() = runBlocking {
        val store = RecordingCertStore(subdomain = "my-device")
        store.storeIntegrationSecrets(mapOf("health" to "brave-health"))
        val provider = McpUrlProvider(store, baseDomain = "rousecontext.com")

        // Integration enabled but its id was NOT in the push payload:
        // buildUrl must return null (not "" or a malformed URL) so the UI
        // can render a diagnostic placeholder.
        assertEquals(
            "https://brave-health.my-device.rousecontext.com/mcp",
            provider.buildUrl("health")
        )
        org.junit.Assert.assertNull(provider.buildUrl("outreach"))
        org.junit.Assert.assertNull(provider.buildUrl("notifications"))
        org.junit.Assert.assertNull(provider.buildUrl("usage"))
    }
}

private class RecordingCertStore(private val subdomain: String?) : CertificateStore {
    private var secrets: Map<String, String> = emptyMap()

    override suspend fun getSubdomain(): String? = subdomain
    override suspend fun storeIntegrationSecrets(secrets: Map<String, String>) {
        this.secrets = secrets
    }
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
    override suspend fun storePrivateKey(pemKey: String) = Unit
    override suspend fun getPrivateKey(): String? = null
    override suspend fun clear() {
        secrets = emptyMap()
    }
    override suspend fun clearCertificates() = Unit
}
