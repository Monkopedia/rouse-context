package com.rousecontext.app.registry

import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.api.McpIntegration
import com.rousecontext.mcp.core.McpServerProvider
import com.rousecontext.mcp.core.ProviderRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * [ProviderRegistry] backed by [McpIntegration] list and [IntegrationStateStore].
 *
 * Resolves path prefixes to providers at request time, respecting the current
 * user-enabled state. Changes take effect immediately without session reconstruction.
 *
 * This registry keeps its own always-on [StateFlow] of enabled IDs so that
 * synchronous callers (FCM receiver, TunnelForegroundService) get up-to-date
 * reads without blocking. The flow is launched eagerly in the provided [appScope].
 */
class IntegrationProviderRegistry(
    integrations: List<McpIntegration>,
    stateStore: IntegrationStateStore,
    appScope: CoroutineScope
) : ProviderRegistry {

    private val integrationsByPath: Map<String, McpIntegration> =
        integrations.associateBy { it.path.removePrefix("/") }

    private val _enabledIds = MutableStateFlow<Set<String>>(emptySet())

    /** Live snapshot of enabled integration IDs. Exposed for tests and UI. */
    val enabledIds: StateFlow<Set<String>> = _enabledIds.asStateFlow()

    init {
        val flows = integrations.map { integration ->
            stateStore.observeUserEnabled(integration.id)
        }
        appScope.launch {
            if (flows.isEmpty()) return@launch
            combine(flows) { values ->
                integrations
                    .mapIndexedNotNull { index, integration ->
                        if (values[index]) integration.id else null
                    }
                    .toSet()
            }.collect { _enabledIds.value = it }
        }
    }

    override fun providerForPath(path: String): McpServerProvider? {
        val integration = integrationsByPath[path] ?: return null
        if (integration.id !in _enabledIds.value) return null
        return integration.provider
    }

    override fun enabledPaths(): Set<String> = integrationsByPath.entries
        .filter { it.value.id in _enabledIds.value }
        .map { it.key }
        .toSet()
}
