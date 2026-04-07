package com.rousecontext.app.ui.viewmodels

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.lifecycle.ViewModel
import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.notifications.capture.NotificationCaptureService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class NotificationSetupState(
    val permissionGranted: Boolean = false,
    val retentionDays: Int = DEFAULT_RETENTION_DAYS,
    val allowActions: Boolean = false
) {
    companion object {
        const val DEFAULT_RETENTION_DAYS = 7
    }
}

/**
 * Manages the Notification integration setup flow: checks listener
 * permission state, persists retention period and action toggle, and
 * enables the integration in the state store.
 */
class NotificationSetupViewModel(
    private val context: Context,
    private val stateStore: IntegrationStateStore
) : ViewModel() {

    private val _state = MutableStateFlow(NotificationSetupState())
    val state: StateFlow<NotificationSetupState> = _state.asStateFlow()

    /** Re-check permission state (call when user returns from Settings). */
    fun refreshPermission() {
        _state.update { it.copy(permissionGranted = isListenerEnabled()) }
    }

    fun setRetentionDays(days: Int) {
        _state.update { it.copy(retentionDays = days) }
    }

    fun setAllowActions(allow: Boolean) {
        _state.update { it.copy(allowActions = allow) }
    }

    /**
     * Enable the integration. Returns true if the required listener
     * permission is granted and the integration was enabled.
     */
    fun enable(): Boolean {
        if (!_state.value.permissionGranted) return false
        stateStore.setUserEnabled(INTEGRATION_ID, true)
        return true
    }

    private fun isListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val component = ComponentName(context, NotificationCaptureService::class.java)
        return flat.contains(component.flattenToString())
    }

    companion object {
        const val INTEGRATION_ID = "notifications"
        val RETENTION_OPTIONS = listOf(1, 7, 30, 90)
    }
}
