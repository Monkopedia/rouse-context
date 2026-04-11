package com.rousecontext.app.ui.viewmodels

import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import androidx.lifecycle.ViewModel
import com.rousecontext.api.IntegrationStateStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class UsageSetupState(val permissionGranted: Boolean = false)

/**
 * Manages the Usage Stats integration setup flow: checks usage access
 * permission and enables the integration in the state store.
 */
class UsageSetupViewModel(
    private val context: Context,
    private val stateStore: IntegrationStateStore
) : ViewModel() {

    private val _state = MutableStateFlow(UsageSetupState())
    val state: StateFlow<UsageSetupState> = _state.asStateFlow()

    /** Re-check permission state (call when user returns from Settings). */
    fun refreshPermission() {
        _state.update { it.copy(permissionGranted = isUsageAccessGranted()) }
    }

    /**
     * Enable the integration. Returns true if the required usage access
     * permission is granted and the integration was enabled.
     */
    fun enable(): Boolean {
        if (!_state.value.permissionGranted) return false
        stateStore.setUserEnabled(INTEGRATION_ID, true)
        return true
    }

    private fun isUsageAccessGranted(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    companion object {
        const val INTEGRATION_ID = "usage"
    }
}
