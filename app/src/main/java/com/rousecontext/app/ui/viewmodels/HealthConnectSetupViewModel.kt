package com.rousecontext.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import com.rousecontext.api.IntegrationStateStore

/**
 * Manages the Health Connect setup flow: tracks permission grant result
 * and enables the integration in the state store on success.
 */
class HealthConnectSetupViewModel(
    private val stateStore: IntegrationStateStore
) : ViewModel() {

    /**
     * Called after the Health Connect permission request completes.
     * If any permissions were granted, marks the integration as enabled.
     *
     * @return true if permissions were granted and integration was enabled
     */
    fun onPermissionsResult(grantedPermissions: Set<String>): Boolean {
        if (grantedPermissions.isEmpty()) return false
        stateStore.setUserEnabled(HEALTH_INTEGRATION_ID, true)
        return true
    }

    companion object {
        const val HEALTH_INTEGRATION_ID = "health"
    }
}
