package com.rousecontext.app.ui.viewmodels

import android.app.NotificationManager
import android.content.Context
import androidx.lifecycle.ViewModel
import com.rousecontext.api.IntegrationStateStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val stateStore: IntegrationStateStore
) : ViewModel() {

    private val _state = MutableStateFlow(OutreachSetupState())
    val state: StateFlow<OutreachSetupState> = _state.asStateFlow()

    /** Re-check DND permission state (call when user returns from Settings). */
    fun refreshPermission() {
        _state.update { it.copy(dndPermissionGranted = isDndGranted()) }
    }

    fun setDndToggled(toggled: Boolean) {
        _state.update { it.copy(dndToggled = toggled) }
    }

    /** Enable the integration. Always succeeds (no required permissions). */
    fun enable() {
        stateStore.setUserEnabled(INTEGRATION_ID, true)
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
