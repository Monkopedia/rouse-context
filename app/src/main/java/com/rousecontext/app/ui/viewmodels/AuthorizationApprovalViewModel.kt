package com.rousecontext.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rousecontext.mcp.core.AuthorizationCodeManager
import com.rousecontext.mcp.core.PendingAuthRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives the authorization approval screen by polling the [AuthorizationCodeManager]
 * for pending requests and exposing approve/deny actions.
 */
class AuthorizationApprovalViewModel(
    private val authorizationCodeManager: AuthorizationCodeManager
) : ViewModel() {

    private val _pendingRequests = MutableStateFlow<List<PendingAuthRequest>>(emptyList())
    val pendingRequests: StateFlow<List<PendingAuthRequest>> = _pendingRequests.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                _pendingRequests.value = authorizationCodeManager.pendingRequests()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun approve(displayCode: String) {
        authorizationCodeManager.approve(displayCode)
        _pendingRequests.value = authorizationCodeManager.pendingRequests()
    }

    fun deny(displayCode: String) {
        authorizationCodeManager.deny(displayCode)
        _pendingRequests.value = authorizationCodeManager.pendingRequests()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 2_000L
    }
}
