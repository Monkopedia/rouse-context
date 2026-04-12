package com.rousecontext.app.ui.viewmodels

import android.app.NotificationManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.app.state.IntegrationSettingsStore
import com.rousecontext.app.ui.screens.SetupMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class OutreachSetupState(
    val dndToggled: Boolean = false,
    val dndPermissionGranted: Boolean = false
)

/**
 * Manages the Outreach integration setup flow: tracks DND permission
 * state and enables the integration in the state store.
 */
class OutreachSetupViewModel(
    private val context: Context,
    private val stateStore: IntegrationStateStore,
    private val settingsStore: IntegrationSettingsStore
) : ViewModel() {

    private val _state = MutableStateFlow(OutreachSetupState())
    val state: StateFlow<OutreachSetupState> = _state.asStateFlow()

    /**
     * Last persisted value of [OutreachSetupState.dndToggled]. Used to compute
     * [isDirty] so the floating Save bar only appears when the user changes
     * the DND toggle away from its saved value.
     */
    private val savedDndToggled = MutableStateFlow(false)

    /**
     * Emits `true` when the in-memory DND toggle differs from the last loaded
     * or saved value. Drives the floating Save bar visibility in SETTINGS mode.
     */
    val isDirty: StateFlow<Boolean> = combine(_state, savedDndToggled) { current, saved ->
        current.dndToggled != saved
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = false
    )

    /** Load persisted settings when opening in SETTINGS mode. */
    fun initForMode(mode: SetupMode) {
        if (mode == SetupMode.SETTINGS) {
            val toggled = settingsStore.getBoolean(
                INTEGRATION_ID,
                IntegrationSettingsStore.KEY_DND_TOGGLED
            )
            _state.update { it.copy(dndToggled = toggled) }
            savedDndToggled.value = toggled
        }
    }

    /** Re-check DND permission state (call when user returns from Settings). */
    fun refreshPermission() {
        _state.update { it.copy(dndPermissionGranted = isDndGranted()) }
    }

    fun setDndToggled(toggled: Boolean) {
        _state.update { it.copy(dndToggled = toggled) }
    }

    /** Enable the integration. Always succeeds (no required permissions). */
    fun enable() {
        persistSettings()
        stateStore.setUserEnabled(INTEGRATION_ID, true)
    }

    /** Save settings without changing the enabled state (SETTINGS mode). */
    fun saveSettings() {
        persistSettings()
    }

    private fun persistSettings() {
        val toggled = _state.value.dndToggled
        settingsStore.setBoolean(
            INTEGRATION_ID,
            IntegrationSettingsStore.KEY_DND_TOGGLED,
            toggled
        )
        savedDndToggled.value = toggled
    }

    /**
     * Returns true if DND permission needs to be requested:
     * user toggled DND on but permission is not granted.
     */
    fun needsDndPermission(): Boolean {
        val s = _state.value
        return s.dndToggled && !s.dndPermissionGranted
    }

    private fun isDndGranted(): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java)
        return nm.isNotificationPolicyAccessGranted
    }

    companion object {
        const val INTEGRATION_ID = "outreach"
    }
}
