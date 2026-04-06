package com.rousecontext.app.state

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.rousecontext.api.IntegrationStateStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.integrationDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "integration_state")

/**
 * [IntegrationStateStore] backed by Preferences DataStore.
 *
 * Note: The interface defines synchronous methods, so we use [runBlocking]
 * internally for reads. This is acceptable because DataStore reads from a
 * cached in-memory snapshot after the first access.
 */
class DataStoreIntegrationStateStore(
    private val context: Context
) : IntegrationStateStore {

    private val dataStore get() = context.integrationDataStore

    override fun isUserEnabled(integrationId: String): Boolean {
        return runBlocking {
            dataStore.data.first()[enabledKey(integrationId)] ?: false
        }
    }

    override fun setUserEnabled(integrationId: String, enabled: Boolean) {
        runBlocking {
            dataStore.edit { prefs ->
                prefs[enabledKey(integrationId)] = enabled
                if (enabled) {
                    prefs[everEnabledKey(integrationId)] = true
                }
            }
        }
    }

    override fun observeUserEnabled(integrationId: String): Flow<Boolean> {
        return dataStore.data.map { prefs ->
            prefs[enabledKey(integrationId)] ?: false
        }
    }

    override fun wasEverEnabled(integrationId: String): Boolean {
        return runBlocking {
            dataStore.data.first()[everEnabledKey(integrationId)] ?: false
        }
    }

    override fun observeChanges(): Flow<Unit> {
        return dataStore.data.map { }
    }

    companion object {
        private fun enabledKey(id: String) = booleanPreferencesKey("enabled_$id")
        private fun everEnabledKey(id: String) = booleanPreferencesKey("ever_enabled_$id")
    }
}
