package com.rousecontext.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rousecontext.app.ui.screens.DeviceCodeApprovalState
import com.rousecontext.mcp.core.TokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages the device code entry and approval/denial flow.
 */
class DeviceCodeApprovalViewModel(
    private val tokenStore: TokenStore
) : ViewModel() {

    private val _state = MutableStateFlow(DeviceCodeApprovalState())
    val state: StateFlow<DeviceCodeApprovalState> = _state.asStateFlow()

    private var pendingIntegrationId: String = ""

    fun configure(integrationName: String, integrationId: String) {
        pendingIntegrationId = integrationId
        _state.value = DeviceCodeApprovalState(integrationName = integrationName)
    }

    fun onCodeChanged(code: String) {
        _state.value = _state.value.copy(enteredCode = code)
    }

    fun approve() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isApproving = true)
            // TODO: validate device code via DeviceCodeManager and create token
            _state.value = _state.value.copy(isApproving = false)
        }
    }

    fun deny() {
        _state.value = DeviceCodeApprovalState()
    }
}
