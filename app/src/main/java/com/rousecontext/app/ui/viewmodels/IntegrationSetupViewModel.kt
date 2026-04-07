package com.rousecontext.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.app.ui.screens.SettingUpState
import com.rousecontext.app.ui.screens.SettingUpVariant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Orchestrates the integration setup flow: enables the integration,
 * triggers cert provisioning, and tracks progress.
 */
class IntegrationSetupViewModel(
    private val stateStore: IntegrationStateStore
) : ViewModel() {

    private val _state = MutableStateFlow(SettingUpState())
    val state: StateFlow<SettingUpState> = _state.asStateFlow()

    private var integrationId: String = ""

    fun startSetup(id: String) {
        integrationId = id
        stateStore.setUserEnabled(id, true)
        _state.value = SettingUpState(variant = SettingUpVariant.FirstTime())
        beginProvisioning()
    }

    fun startRefresh(id: String) {
        integrationId = id
        _state.value = SettingUpState(variant = SettingUpVariant.Refreshing)
        beginProvisioning()
    }

    private fun beginProvisioning() {
        viewModelScope.launch {
            // TODO: trigger actual ACME cert provisioning via CertificateStore
            // For now the flow immediately completes; the real implementation
            // will update state as provisioning progresses.
        }
    }
}
