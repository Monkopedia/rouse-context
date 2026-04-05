package com.rousecontext.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rousecontext.api.IntegrationState
import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.api.McpIntegration
import com.rousecontext.api.deriveIntegrationState
import com.rousecontext.app.ui.screens.PickerIntegration
import com.rousecontext.app.ui.screens.PickerIntegrationState
import com.rousecontext.mcp.core.TokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Lists available integrations for the Add Integration picker,
 * mapping each to the correct [PickerIntegrationState].
 */
class AddIntegrationViewModel(
    private val integrations: List<McpIntegration>,
    private val stateStore: IntegrationStateStore,
    private val tokenStore: TokenStore
) : ViewModel() {

    private val refreshTrigger = MutableStateFlow(0)

    val pickerIntegrations: StateFlow<List<PickerIntegration>> = refreshTrigger
        .map {
            integrations.map { integration ->
                val derived = deriveIntegrationState(
                    userEnabled = stateStore.isUserEnabled(integration.id),
                    wasEverEnabled = stateStore.wasEverEnabled(integration.id),
                    isAvailable = integration.isAvailable(),
                    hasTokens = tokenStore.hasTokens(integration.id)
                )
                PickerIntegration(
                    id = integration.id,
                    name = integration.displayName,
                    description = integration.description,
                    state = when (derived) {
                        IntegrationState.Available -> PickerIntegrationState.AVAILABLE
                        IntegrationState.Disabled -> PickerIntegrationState.DISABLED
                        IntegrationState.Unavailable -> PickerIntegrationState.UNAVAILABLE
                        else -> PickerIntegrationState.AVAILABLE
                    }
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList()
        )

    fun refresh() {
        viewModelScope.launch {
            refreshTrigger.value++
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
