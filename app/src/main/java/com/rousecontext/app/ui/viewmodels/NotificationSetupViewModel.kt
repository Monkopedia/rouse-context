package com.rousecontext.app.ui.viewmodels

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.app.state.IntegrationSettingsStore
import com.rousecontext.app.ui.screens.SetupMode
import com.rousecontext.integrations.notifications.NotificationCaptureService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    private val stateStore: IntegrationStateStore,
    private val settingsStore: IntegrationSettingsStore
) : ViewModel() {

    private val _state = MutableStateFlow(NotificationSetupState())
    val state: StateFlow<NotificationSetupState> = _state.asStateFlow()

    /**
     * Snapshot of the persisted values the last time settings were loaded or
     * saved. Used to compute [isDirty] so the floating Save bar only appears
     * when the user has actually changed something.
     */
    private val savedSnapshot = MutableStateFlow(
        SavedSnapshot(
            retentionDays = NotificationSetupState.DEFAULT_RETENTION_DAYS,
            allowActions = false
        )
    )

    /**
     * Emits `true` when the in-memory state differs from the last loaded or
     * saved snapshot. Drives the floating Save bar visibility.
     */
    val isDirty: StateFlow<Boolean> = combine(_state, savedSnapshot) { current, saved ->
        current.retentionDays != saved.retentionDays ||
            current.allowActions != saved.allowActions
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = false
    )

    /** Load persisted settings when opening in SETTINGS mode. */
    fun initForMode(mode: SetupMode) {
        if (mode == SetupMode.SETTINGS) {
            viewModelScope.launch {
                val retention = settingsStore.getInt(
                    INTEGRATION_ID,
                    IntegrationSettingsStore.KEY_RETENTION_DAYS,
                    NotificationSetupState.DEFAULT_RETENTION_DAYS
                )
                val allow = settingsStore.getBoolean(
                    INTEGRATION_ID,
                    IntegrationSettingsStore.KEY_ALLOW_ACTIONS
                )
                _state.update {
                    it.copy(retentionDays = retention, allowActions = allow)
                }
                savedSnapshot.value =
                    SavedSnapshot(retentionDays = retention, allowActions = allow)
            }
        }
    }

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
        viewModelScope.launch {
            persistSettings()
            stateStore.setUserEnabled(INTEGRATION_ID, true)
        }
        return true
    }

    /** Save settings without changing the enabled state (SETTINGS mode). */
    fun saveSettings() {
        viewModelScope.launch { persistSettings() }
    }

    private suspend fun persistSettings() {
        val s = _state.value
        settingsStore.setInt(
            INTEGRATION_ID,
            IntegrationSettingsStore.KEY_RETENTION_DAYS,
            s.retentionDays
        )
        settingsStore.setBoolean(
            INTEGRATION_ID,
            IntegrationSettingsStore.KEY_ALLOW_ACTIONS,
            s.allowActions
        )
        savedSnapshot.value = SavedSnapshot(
            retentionDays = s.retentionDays,
            allowActions = s.allowActions
        )
    }

    private fun isListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val component = ComponentName(context, NotificationCaptureService::class.java)
        return flat.contains(component.flattenToString())
    }

    private data class SavedSnapshot(val retentionDays: Int, val allowActions: Boolean)

    companion object {
        const val INTEGRATION_ID = "notifications"
        val RETENTION_OPTIONS = listOf(1, 7, 30, 90)
    }
}
