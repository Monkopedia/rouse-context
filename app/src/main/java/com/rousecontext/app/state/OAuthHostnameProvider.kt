package com.rousecontext.app.state

import com.rousecontext.api.McpIntegration
import com.rousecontext.tunnel.CertificateStore

/**
 * Suspending provider for the public OAuth metadata hostname.
 *
 * Replaces the prior Koin-graph-time [kotlinx.coroutines.runBlocking] read of
 * the certificate store. Consumers call [resolve] from within suspend contexts
 * (Ktor routing handlers, startup coroutines). See GitHub issue #136.
 */
class OAuthHostnameProvider(
    private val certStore: CertificateStore,
    private val baseDomain: String,
    private val integrations: List<McpIntegration>
) {

    /**
     * Resolve the OAuth metadata hostname from the certificate store.
     *
     * If the device has been onboarded and a secret exists for the first
     * integration, returns `{secret}.{subdomain}.{baseDomain}`. Otherwise
     * returns `"localhost"`.
     */
    suspend fun resolve(): String {
        val defaultIntegration = integrations.firstOrNull()?.id ?: "health"
        val subdomain = certStore.getSubdomain()
        val secret = certStore.getSecretForIntegration(defaultIntegration)
        return if (subdomain != null && secret != null) {
            "$secret.$subdomain.$baseDomain"
        } else {
            "localhost"
        }
    }
}
