package com.rousecontext.app.registry

import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.api.McpIntegration
import com.rousecontext.mcp.core.McpServerProvider
import com.rousecontext.mcp.core.ProviderRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
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
 *
 * ### Cold-start readiness (issue #414)
 *
 * The [IntegrationStateStore] is DataStore-backed and emits its first value
 * asynchronously. Between construction and the first emission, [enabledPaths]
 * returns `emptySet()` — which historically caused FCM wakes during cold-start
 * to be silently dropped. Callers that gate behavior on the snapshot MUST
 * [awaitReady] (or [awaitReadyBlocking]) first.
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

    private val readyDeferred = CompletableDeferred<Unit>()
    private val readyLatch = CountDownLatch(1)

    init {
        val flows = integrations.map { integration ->
            stateStore.observeUserEnabled(integration.id)
        }
        if (flows.isEmpty()) {
            // No integrations to load — registry is trivially ready.
            signalReady()
        } else {
            appScope.launch {
                combine(flows) { values ->
                    integrations
                        .mapIndexedNotNull { index, integration ->
                            if (values[index]) integration.id else null
                        }
                        .toSet()
                }
                    .onEach { _enabledIds.value = it }
                    .collect { signalReady() }
            }
        }
    }

    private fun signalReady() {
        // Idempotent: subsequent emissions just no-op on the already-completed signals.
        readyDeferred.complete(Unit)
        if (readyLatch.count > 0) {
            readyLatch.countDown()
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

    override suspend fun awaitReady() {
        readyDeferred.await()
    }

    override fun awaitReadyBlocking(timeoutMs: Long): Boolean =
        readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
}
