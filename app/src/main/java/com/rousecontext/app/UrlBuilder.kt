package com.rousecontext.app

import com.rousecontext.tunnel.CertificateStore

/**
 * Builds per-integration MCP endpoint URLs and hostnames.
 *
 * With per-integration hostnames, each integration gets its own secret prefix
 * (e.g. "brave-health" for health), yielding a hostname like:
 *   brave-health.abc123.rousecontext.com
 *
 * The MCP endpoint is always at /mcp on that hostname.
 */
class McpUrlProvider(
    private val certStore: CertificateStore,
    private val baseDomain: String
) {

    /**
     * Builds the full MCP URL for an integration.
     * Returns null if subdomain or secret is unavailable.
     */
    suspend fun buildUrl(integrationId: String): String? {
        val subdomain = certStore.getSubdomain() ?: return null
        val secret = certStore.getSecretForIntegration(integrationId) ?: return null
        return buildMcpUrl(secret, subdomain, baseDomain)
    }

    /**
     * Builds the hostname for an integration (e.g. "brave-health.abc123.rousecontext.com").
     * Returns null if subdomain or secret is unavailable.
     */
    suspend fun buildHostname(integrationId: String): String? {
        val subdomain = certStore.getSubdomain() ?: return null
        val secret = certStore.getSecretForIntegration(integrationId) ?: return null
        return "$secret.$subdomain.$baseDomain"
    }
}

/**
 * Builds the public MCP endpoint URL for an integration.
 *
 * The URL is: https://{integrationSecret}.{subdomain}.{baseDomain}/mcp
 *
 * The path is always /mcp -- the integration is identified by hostname.
 */
fun buildMcpUrl(integrationSecret: String, subdomain: String, baseDomain: String): String {
    return "https://$integrationSecret.$subdomain.$baseDomain/mcp"
}
