package com.rousecontext.app.registry

import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.api.McpIntegration
import com.rousecontext.mcp.core.McpServerProvider
import com.rousecontext.mcp.core.ProviderRegistry

/**
 * [ProviderRegistry] backed by [McpIntegration] list and [IntegrationStateStore].
 *
 * Resolves path prefixes to providers at request time, respecting the current
 * user-enabled state. Changes take effect immediately without session reconstruction.
 */
class IntegrationProviderRegistry(
    integrations: List<McpIntegration>,
    private val stateStore: IntegrationStateStore
) : ProviderRegistry {

    private val integrationsByPath: Map<String, McpIntegration> =
        integrations.associateBy { it.path.removePrefix("/") }

    override fun providerForPath(path: String): McpServerProvider? {
        val integration = integrationsByPath[path] ?: return null
        if (!stateStore.isUserEnabled(integration.id)) return null
        return integration.provider
    }

    override fun enabledPaths(): Set<String> {
        return integrationsByPath.entries
            .filter { stateStore.isUserEnabled(it.value.id) }
            .map { it.key }
            .toSet()
    }
}
