package com.rousecontext.mcp.core

/**
 * Live registry of enabled integration providers.
 *
 * The app implements this backed by [IntegrationStateStore]. McpSession queries it
 * per-request to resolve path prefixes to providers.
 *
 * Changes (enable/disable) are reflected immediately - no session reconstruction needed.
 */
interface ProviderRegistry {

    /**
     * Returns the provider registered at the given path prefix, or null if no provider
     * is registered or the integration is disabled.
     *
     * @param path first path segment from the HTTP request (e.g. "health", "notifications")
     */
    fun providerForPath(path: String): McpServerProvider?

    /**
     * Returns the set of currently enabled path prefixes (e.g. ["health", "notifications"]).
     */
    fun enabledPaths(): Set<String>
}
