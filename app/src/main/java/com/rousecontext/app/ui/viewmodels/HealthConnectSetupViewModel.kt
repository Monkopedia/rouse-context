package com.rousecontext.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.integrations.health.HEALTH_DATA_HISTORY_PERMISSION
import com.rousecontext.integrations.health.HealthConnectRepository
import com.rousecontext.integrations.health.RecordTypeRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages the Health Connect setup flow: tracks permission grant result
 * and enables the integration in the state store on success.
 *
 * Also surfaces whether the optional "historical data" read permission
 * (data recorded before the app was installed / granted access) has been
 * granted, so the settings screen can show a prompt/indicator, and the
 * set of record-type permissions currently granted so the settings list
 * can render per-row checked/unchecked indicators (#99).
 */
class HealthConnectSetupViewModel(
    private val stateStore: IntegrationStateStore,
    private val healthRepository: HealthConnectRepository
) : ViewModel() {

    private val _historicalAccessGranted = MutableStateFlow(false)

    /**
     * Whether the user has granted read access to historical Health Connect data.
     *
     * Call [refreshPermissions] to query the current state from the Health
     * Connect SDK (e.g. on screen entry or after returning from the permission
     * request flow).
     */
    val historicalAccessGranted: StateFlow<Boolean> = _historicalAccessGranted.asStateFlow()

    private val _grantedRecordTypes = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Set of record-type names (matching
     * [com.rousecontext.integrations.health.RecordTypeRegistry] keys, e.g. `"Steps"`,
     * `"HeartRate"`) that currently have read permission granted.
     *
     * Updated by [refreshPermissions] and by [onPermissionsResult]. Used by
     * the Health Connect settings screen to render a check/uncheck indicator
     * on each record-type row and a `granted/total` summary in each category
     * header (#99).
     */
    val grantedRecordTypes: StateFlow<Set<String>> = _grantedRecordTypes.asStateFlow()

    /**
     * Queries the repository for the current permission state and updates
     * [historicalAccessGranted] and [grantedRecordTypes].
     *
     * Safe to call from lifecycle callbacks (e.g. `ON_RESUME`) so that
     * revocations performed in the Health Connect system UI are reflected
     * when the user returns to the app (#99).
     */
    fun refreshPermissions() {
        viewModelScope.launch {
            val historical = try {
                healthRepository.isHistoricalReadGranted()
            } catch (_: Exception) {
                false
            }
            val granted = try {
                healthRepository.getGrantedPermissions()
            } catch (_: Exception) {
                emptySet()
            }
            _historicalAccessGranted.value = historical
            _grantedRecordTypes.value = granted
        }
    }

    /**
     * Called after the Health Connect permission request completes.
     * If any permissions were granted, marks the integration as enabled.
     *
     * Also updates [historicalAccessGranted] and [grantedRecordTypes] from
     * [grantedPermissions] immediately, then triggers a [refreshPermissions]
     * to reconcile with Health Connect.
     */
    fun onPermissionsResult(grantedPermissions: Set<String>) {
        _historicalAccessGranted.value = HISTORY_PERMISSION in grantedPermissions
        // Publish the result synchronously: the setup screen recomposes as
        // soon as the permission dialog returns, and since #537 it stays on
        // screen afterwards, so the post-grant state (the "Continue" button,
        // the enabled historical-access prompt) must be correct on that first
        // recomposition rather than a beat later when the refresh lands.
        _grantedRecordTypes.value = RecordTypeRegistry.namesForPermissions(grantedPermissions)
        refreshPermissions()
        if (grantedPermissions.isEmpty()) return
        viewModelScope.launch {
            stateStore.setUserEnabled(HEALTH_INTEGRATION_ID, true)
        }
    }

    /**
     * Called after the historical-data permission request result is returned.
     *
     * Updates [historicalAccessGranted] based on the granted set. Does NOT
     * toggle integration-enabled state (this flow is strictly additive on top
     * of the main setup).
     */
    fun onHistoricalPermissionResult(grantedPermissions: Set<String>) {
        _historicalAccessGranted.value = HISTORY_PERMISSION in grantedPermissions
    }

    companion object {
        const val HEALTH_INTEGRATION_ID = "health"

        /** Health Connect history-read permission string. */
        const val HISTORY_PERMISSION: String = HEALTH_DATA_HISTORY_PERMISSION
    }
}
