package com.rousecontext.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rousecontext.api.IntegrationState
import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.api.McpIntegration
import com.rousecontext.api.deriveIntegrationState
import com.rousecontext.app.ui.screens.AddClientState
import com.rousecontext.app.ui.screens.EndpointItem
import com.rousecontext.mcp.core.TokenStore
import com.rousecontext.tunnel.CertificateStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/**
 * Provides the MCP endpoint URLs for each enabled integration,
 * combining the device subdomain with each integration's path.
 */
class AddClientViewModel(
    private val integrations: List<McpIntegration>,
    private val stateStore: IntegrationStateStore,
    private val tokenStore: TokenStore,
    private val certificateStore: CertificateStore
) : ViewModel() {

    val state: StateFlow<AddClientState> = flow {
        val subdomain = certificateStore.getSubdomain()
        val host = subdomain?.let { "$it.rousecontext.com" } ?: "your-device.rousecontext.com"

        val endpoints = integrations.mapNotNull { integration ->
            val derived = deriveIntegrationState(
                userEnabled = stateStore.isUserEnabled(integration.id),
                wasEverEnabled = stateStore.wasEverEnabled(integration.id),
                isAvailable = integration.isAvailable(),
                hasTokens = tokenStore.hasTokens(integration.id)
            )
            if (derived == IntegrationState.Active || derived == IntegrationState.Pending) {
                EndpointItem(
                    integrationName = integration.displayName,
                    url = "https://$host${integration.path}/mcp"
                )
            } else {
                null
            }
        }

        emit(AddClientState(endpoints = endpoints))
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = AddClientState()
    )

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
