package com.rousecontext.app.ui.viewmodels

import android.app.NotificationManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rousecontext.app.ui.screens.AuthorizationApprovalItem
import com.rousecontext.app.ui.screens.AuthorizationApprovalUiState
import com.rousecontext.mcp.core.AuthorizationCodeManager
import com.rousecontext.mcp.core.DeviceCodeManager
import com.rousecontext.mcp.core.PendingAuthRequest
import com.rousecontext.mcp.core.PendingDeviceCode
import com.rousecontext.notifications.AuthRequestNotifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Drives the authorization approval screen by observing
 * [AuthorizationCodeManager.pendingRequestsFlow] and
 * [DeviceCodeManager.pendingCodesFlow], and exposing approve/deny actions that
 * resolve against whichever manager owns the code.
 *
 * The previous implementation used a `while (isActive) { … delay(POLL_INTERVAL_MS) }`
 * loop, which hung under `kotlinx-coroutines-test` because the real-time delay loop
 * is not driven by the test scheduler. The Flow-based design cooperates cleanly with
 * virtual time and doesn't need periodic wakeups.
 */
class AuthorizationApprovalViewModel(
    private val authorizationCodeManager: AuthorizationCodeManager,
    private val deviceCodeManager: DeviceCodeManager,
    private val notificationManager: NotificationManager
) : ViewModel() {

    /**
     * Both approval sources as one list. The device flow (RFC 8628) mints user
     * codes the user approves on exactly the same screen, and before #606 they
     * were never shown here at all -- so the notification's destination was empty
     * for every device-flow request.
     */
    private val allPendingRequests: Flow<List<PendingAuthRequest>> = combine(
        authorizationCodeManager.pendingRequestsFlow,
        deviceCodeManager.pendingCodesFlow
    ) { authRequests, deviceCodes ->
        authRequests + deviceCodes.map { it.toPendingAuthRequest() }
    }

    val pendingRequests: StateFlow<List<PendingAuthRequest>> = allPendingRequests
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            // Seed from both managers synchronously. `stateIn`'s collector only
            // runs once the scope's dispatcher does, and callers (plus tests)
            // rely on requests that already exist at construction being visible
            // in the very first value.
            initialValue = currentPendingRequests()
        )

    /**
     * UI state derived from [pendingRequests]. Starts as [AuthorizationApprovalUiState.Loading]
     * and becomes [AuthorizationApprovalUiState.Loaded] as soon as the collector is active.
     * Neither source flow can error, so there is no error branch here.
     * [retry] exists for API stability with #65.
     */
    val uiState: StateFlow<AuthorizationApprovalUiState> = allPendingRequests
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
        if (!authorizationCodeManager.approve(displayCode)) {
            deviceCodeManager.approve(displayCode)
        }
        cancelAuthNotifications()
    }

    fun deny(displayCode: String) {
        if (!authorizationCodeManager.deny(displayCode)) {
            deviceCodeManager.deny(displayCode)
        }
        cancelAuthNotifications()
    }

    private fun currentPendingRequests(): List<PendingAuthRequest> =
        authorizationCodeManager.pendingRequestsFlow.value +
            deviceCodeManager.pendingCodesFlow.value.map { it.toPendingAuthRequest() }

    /**
     * A device-flow request rendered as an approval item. `clientId` matches the
     * id [com.rousecontext.mcp.core.DeviceCodeManager] grants the token under;
     * the device flow has no registered client name to show.
     */
    private fun PendingDeviceCode.toPendingAuthRequest(): PendingAuthRequest = PendingAuthRequest(
        displayCode = userCode,
        integration = integrationId,
        clientId = DEVICE_CODE_CLIENT_ID,
        clientName = null,
        createdAt = createdAt
    )

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
        private const val DEVICE_CODE_CLIENT_ID = "device-code-client"
    }
}
