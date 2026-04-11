package com.rousecontext.app.state

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.integrationSettingsDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "integration_settings")

/**
 * Persists per-integration settings (e.g. notification retention days,
 * allow-actions toggle, outreach DND toggle) in Preferences DataStore.
 *
 * This is separate from [DataStoreIntegrationStateStore] which only tracks
 * the enabled/disabled state. Each setting is keyed by integration ID + name.
 */
class IntegrationSettingsStore(private val context: Context) {

    private val dataStore get() = context.integrationSettingsDataStore

    fun getBoolean(integrationId: String, key: String, default: Boolean = false): Boolean =
        runBlocking {
            dataStore.data.first()[booleanKey(integrationId, key)] ?: default
        }

    fun setBoolean(integrationId: String, key: String, value: Boolean) {
        runBlocking {
            dataStore.edit { prefs ->
                prefs[booleanKey(integrationId, key)] = value
            }
        }
    }

    fun getInt(integrationId: String, key: String, default: Int): Int = runBlocking {
        dataStore.data.first()[intKey(integrationId, key)] ?: default
    }

    fun setInt(integrationId: String, key: String, value: Int) {
        runBlocking {
            dataStore.edit { prefs ->
                prefs[intKey(integrationId, key)] = value
            }
        }
    }

    companion object {
        const val KEY_ALLOW_ACTIONS = "allow_actions"
        const val KEY_RETENTION_DAYS = "retention_days"
        const val KEY_DND_TOGGLED = "dnd_toggled"

        private fun booleanKey(id: String, key: String) = booleanPreferencesKey("${id}_$key")

        private fun intKey(id: String, key: String) = intPreferencesKey("${id}_$key")
    }
}
