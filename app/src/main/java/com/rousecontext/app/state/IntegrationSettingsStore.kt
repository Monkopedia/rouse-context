package com.rousecontext.app.state

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.integrationSettingsDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "integration_settings")

/**
 * Persists per-integration settings (e.g. notification retention days,
 * allow-actions toggle, outreach DND toggle) in Preferences DataStore.
 *
 * This is separate from [DataStoreIntegrationStateStore] which only tracks
 * the enabled/disabled state. Each setting is keyed by integration ID + name.
 *
 * All reads are suspending; UI layers should consume values via
 * [com.rousecontext.app.state.PreferencesSnapshotHolder] rather than blocking
 * on these reads.
 */
class IntegrationSettingsStore(private val context: Context) {

    private val dataStore get() = context.integrationSettingsDataStore

    suspend fun getBoolean(integrationId: String, key: String, default: Boolean = false): Boolean =
        dataStore.data.first()[booleanKey(integrationId, key)] ?: default

    suspend fun setBoolean(integrationId: String, key: String, value: Boolean) {
        dataStore.edit { prefs ->
            prefs[booleanKey(integrationId, key)] = value
        }
    }

    suspend fun getInt(integrationId: String, key: String, default: Int): Int =
        dataStore.data.first()[intKey(integrationId, key)] ?: default

    suspend fun setInt(integrationId: String, key: String, value: Int) {
        dataStore.edit { prefs ->
            prefs[intKey(integrationId, key)] = value
        }
    }

    /** Observe a boolean setting as a Flow. */
    fun observeBoolean(
        integrationId: String,
        key: String,
        default: Boolean = false
    ): Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[booleanKey(integrationId, key)] ?: default }

    /** Observe an int setting as a Flow. */
    fun observeInt(integrationId: String, key: String, default: Int): Flow<Int> =
        dataStore.data.map { prefs -> prefs[intKey(integrationId, key)] ?: default }

    /** Returns the full preferences snapshot as a Flow. Used by the snapshot holder. */
    fun observeAll(): Flow<Preferences> = dataStore.data

    companion object {
        const val KEY_ALLOW_ACTIONS = "allow_actions"
        const val KEY_RETENTION_DAYS = "retention_days"
        const val KEY_DND_TOGGLED = "dnd_toggled"

        /**
         * Outreach: user opted in to SYSTEM_ALERT_WINDOW so the app can start
         * activities (open_link / launch_app) directly from the background on
         * Android 14+. When false, those tools post a tap-to-launch notification.
         * See GitHub issue #102.
         */
        const val KEY_DIRECT_LAUNCH_ENABLED = "direct_launch_enabled"

        fun booleanKey(id: String, key: String) = booleanPreferencesKey("${id}_$key")

        fun intKey(id: String, key: String) = intPreferencesKey("${id}_$key")
    }
}
