package com.rousecontext.app

import com.rousecontext.tunnel.CertificateStore

/**
 * Builds the public MCP endpoint URL for an integration.
 *
 * URL format: https://{secretPrefix}.{subdomain}.{baseDomain}{path}/mcp
 *
 * A secret prefix is always required. Missing prefix indicates a device
 * that hasn't completed registration and should not serve URLs.
 */
fun buildMcpUrl(
    secretPrefix: String,
    subdomain: String,
    baseDomain: String,
    integrationPath: String
): String {
    val host = "$secretPrefix.$subdomain.$baseDomain"
    return "https://$host$integrationPath/mcp"
}

/**
 * Central URL provider that resolves device identity from the CertificateStore
 * and builds MCP URLs. Inject this instead of manually reading certStore +
 * BuildConfig in every ViewModel / composable.
 */
class McpUrlProvider(
    private val certStore: CertificateStore,
    private val baseDomain: String = BuildConfig.RELAY_HOST.removePrefix("relay.")
) {
    /**
     * Build the full MCP endpoint URL for the given integration path.
     * Returns null if the device hasn't completed registration
     * (missing subdomain or secret prefix).
     */
    suspend fun buildUrl(integrationPath: String): String? {
        val subdomain = certStore.getSubdomain() ?: return null
        val secretPrefix = certStore.getSecretPrefix() ?: return null
        return buildMcpUrl(secretPrefix, subdomain, baseDomain, integrationPath)
    }

    /**
     * Build the hostname portion (no scheme, no path) for use in MCP session setup.
     * Returns null if registration is incomplete.
     */
    suspend fun buildHostname(): String? {
        val subdomain = certStore.getSubdomain() ?: return null
        val secretPrefix = certStore.getSecretPrefix() ?: return null
        return "$secretPrefix.$subdomain.$baseDomain"
    }
}
