package com.rousecontext.app.ui.viewmodels

import android.app.NotificationManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rousecontext.app.ui.screens.AuthorizationApprovalItem
import com.rousecontext.app.ui.screens.AuthorizationApprovalUiState
import com.rousecontext.mcp.core.AuthorizationCodeManager
import com.rousecontext.mcp.core.PendingAuthRequest
import com.rousecontext.notifications.AuthRequestNotifier
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Drives the authorization approval screen by observing
 * [AuthorizationCodeManager.pendingRequestsFlow] and exposing approve/deny actions.
 *
 * The previous implementation used a `while (isActive) { … delay(POLL_INTERVAL_MS) }`
 * loop, which hung under `kotlinx-coroutines-test` because the real-time delay loop
 * is not driven by the test scheduler. The Flow-based design cooperates cleanly with
 * virtual time and doesn't need periodic wakeups.
 */
class AuthorizationApprovalViewModel(
    private val authorizationCodeManager: AuthorizationCodeManager,
    private val notificationManager: NotificationManager
) : ViewModel() {

    val pendingRequests: StateFlow<List<PendingAuthRequest>> =
        authorizationCodeManager.pendingRequestsFlow

    /**
     * UI state derived from [pendingRequests]. Starts as [AuthorizationApprovalUiState.Loading]
     * and becomes [AuthorizationApprovalUiState.Loaded] as soon as the collector is active.
     * The manager's `pendingRequestsFlow` cannot error, so there is no error branch here.
     * [retry] exists for API stability with #65.
     */
    val uiState: StateFlow<AuthorizationApprovalUiState> =
        authorizationCodeManager.pendingRequestsFlow
            .map { list ->
                AuthorizationApprovalUiState.Loaded(list.map { it.toUiItem() })
                    as AuthorizationApprovalUiState
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = AuthorizationApprovalUiState.Loading
            )

    /** No-op retained for API compatibility with #65. The flow is always live. */
    fun retry() {
        // No-op: pendingRequestsFlow cannot enter an error state.
    }

    fun approve(displayCode: String) {
        authorizationCodeManager.approve(displayCode)
        cancelAuthNotifications()
    }

    fun deny(displayCode: String) {
        authorizationCodeManager.deny(displayCode)
        cancelAuthNotifications()
    }

    private fun PendingAuthRequest.toUiItem(): AuthorizationApprovalItem =
        AuthorizationApprovalItem(
            displayCode = displayCode,
            integration = integration
        )

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
        private const val MAX_NOTIFICATIONS = 50
    }
}
