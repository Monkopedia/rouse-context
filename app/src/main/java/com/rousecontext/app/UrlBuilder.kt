package com.rousecontext.app

/**
 * Builds the public MCP endpoint URL for an integration.
 *
 * When a secret prefix is present, the URL is:
 *   https://{secretPrefix}.{subdomain}.{baseDomain}{path}/mcp
 *
 * When no secret prefix exists (legacy devices), falls back to:
 *   https://{subdomain}.{baseDomain}{path}/mcp
 */
fun buildMcpUrl(
    secretPrefix: String?,
    subdomain: String,
    baseDomain: String,
    integrationPath: String
): String {
    val host = if (secretPrefix != null) {
        "$secretPrefix.$subdomain.$baseDomain"
    } else {
        "$subdomain.$baseDomain"
    }
    return "https://$host$integrationPath/mcp"
}
