package com.rousecontext.api

import kotlinx.coroutines.flow.Flow

/**
 * User-toggled enable/disable state per integration.
 *
 * The app implements this backed by Preferences DataStore. Integration modules
 * and the provider registry query it to determine whether an integration should
 * be active.
 */
interface IntegrationStateStore {

    /** Returns whether the user has enabled the given integration. */
    suspend fun isUserEnabled(integrationId: String): Boolean

    /**
     * Sets the user-enabled state for an integration.
     *
     * Setting [enabled] to true also marks the integration as "ever enabled",
     * which affects state derivation (Available vs Disabled).
     */
    suspend fun setUserEnabled(integrationId: String, enabled: Boolean)

    /**
     * Observes the user-enabled state for an integration as a [Flow].
     * Emits the current value immediately, then subsequent changes.
     */
    fun observeUserEnabled(integrationId: String): Flow<Boolean>

    /**
     * Returns whether the user has ever enabled this integration.
     * Used to distinguish [IntegrationState.Available] from [IntegrationState.Disabled].
     */
    suspend fun wasEverEnabled(integrationId: String): Boolean

    /**
     * Observes whether the user has ever enabled this integration.
     */
    fun observeEverEnabled(integrationId: String): Flow<Boolean>

    /**
     * Emits [Unit] whenever any integration state changes. The ViewModel can
     * combine this with other flows to reactively rebuild derived state.
     */
    fun observeChanges(): Flow<Unit>
}
