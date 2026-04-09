package com.rousecontext.app.ui.viewmodels

import android.app.NotificationManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rousecontext.mcp.core.AuthorizationCodeManager
import com.rousecontext.mcp.core.PendingAuthRequest
import com.rousecontext.notifications.AuthRequestNotifier
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
    private val authorizationCodeManager: AuthorizationCodeManager,
    private val notificationManager: NotificationManager
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
        cancelAuthNotifications()
        _pendingRequests.value = authorizationCodeManager.pendingRequests()
    }

    fun deny(displayCode: String) {
        authorizationCodeManager.deny(displayCode)
        cancelAuthNotifications()
        _pendingRequests.value = authorizationCodeManager.pendingRequests()
    }

    /**
     * Cancel all auth request notifications. The notifier uses incrementing IDs
     * starting from [AuthRequestNotifier.BASE_ID], so we cancel a reasonable range.
     */
    private fun cancelAuthNotifications() {
        val end = AuthRequestNotifier.BASE_ID + MAX_NOTIFICATIONS
        for (id in AuthRequestNotifier.BASE_ID until end) {
            notificationManager.cancel(id)
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 2_000L
        private const val MAX_NOTIFICATIONS = 50
    }
}
