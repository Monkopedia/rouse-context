package com.rousecontext.app.ui.viewmodels

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.provider.Settings
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
    val dndPermissionGranted: Boolean = false,
    /**
     * Whether the user has opted in to direct background-activity launches
     * (SYSTEM_ALERT_WINDOW). Only meaningful on Android 14+; persisted in
     * [IntegrationSettingsStore]. See GitHub issue #102.
     */
    val directLaunchEnabled: Boolean = false,
    /** Whether `Settings.canDrawOverlays` currently returns true. */
    val overlayPermissionGranted: Boolean = false,
    /**
     * Whether direct-launch is even applicable on this device. False on
     * pre-Android-14 (no BAL restriction to work around); true on 34+.
     */
    val directLaunchApplicable: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
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
     * Last persisted values used to compute [isDirty] so the floating Save bar
     * only appears when the user has actually edited something.
     */
    private val savedDndToggled = MutableStateFlow(false)
    private val savedDirectLaunchEnabled = MutableStateFlow(false)

    /**
     * Emits `true` when any in-memory toggle differs from the last loaded or
     * saved value. Drives the floating Save bar visibility in SETTINGS mode.
     */
    val isDirty: StateFlow<Boolean> = combine(
        _state,
        savedDndToggled,
        savedDirectLaunchEnabled
    ) { current, savedDnd, savedDirect ->
        current.dndToggled != savedDnd || current.directLaunchEnabled != savedDirect
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = false
    )

    /** Load persisted settings when opening in SETTINGS mode. */
    fun initForMode(mode: SetupMode) {
        if (mode == SetupMode.SETTINGS) {
            val dnd = settingsStore.getBoolean(
                INTEGRATION_ID,
                IntegrationSettingsStore.KEY_DND_TOGGLED
            )
            val direct = settingsStore.getBoolean(
                INTEGRATION_ID,
                IntegrationSettingsStore.KEY_DIRECT_LAUNCH_ENABLED
            )
            _state.update {
                it.copy(directLaunchEnabled = direct, dndToggled = dnd)
            }
            savedDndToggled.value = dnd
            savedDirectLaunchEnabled.value = direct
        }
    }

    /** Re-check permission state (call when user returns from Settings). */
    fun refreshPermission() {
        _state.update {
            it.copy(
                dndPermissionGranted = isDndGranted(),
                overlayPermissionGranted = isOverlayGranted()
            )
        }
    }

    fun setDndToggled(toggled: Boolean) {
        _state.update { it.copy(dndToggled = toggled) }
    }

    fun setDirectLaunchEnabled(enabled: Boolean) {
        _state.update { it.copy(directLaunchEnabled = enabled) }
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
        val snapshot = _state.value
        settingsStore.setBoolean(
            INTEGRATION_ID,
            IntegrationSettingsStore.KEY_DND_TOGGLED,
            snapshot.dndToggled
        )
        settingsStore.setBoolean(
            INTEGRATION_ID,
            IntegrationSettingsStore.KEY_DIRECT_LAUNCH_ENABLED,
            snapshot.directLaunchEnabled
        )
        savedDndToggled.value = snapshot.dndToggled
        savedDirectLaunchEnabled.value = snapshot.directLaunchEnabled
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

    private fun isOverlayGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return Settings.canDrawOverlays(context)
    }

    companion object {
        const val INTEGRATION_ID = "outreach"
    }
}
