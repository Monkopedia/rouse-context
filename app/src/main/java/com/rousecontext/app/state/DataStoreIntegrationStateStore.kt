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

private val Context.integrationDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "integration_state")

/**
 * [IntegrationStateStore] backed by Preferences DataStore.
 *
 * All reads are suspending — callers in suspend contexts get fresh values;
 * UI layers should prefer observing the [Flow]-returning methods via the
 * [com.rousecontext.app.state.PreferencesSnapshotHolder].
 */
class DataStoreIntegrationStateStore(private val context: Context) : IntegrationStateStore {

    private val dataStore get() = context.integrationDataStore

    override suspend fun isUserEnabled(integrationId: String): Boolean =
        dataStore.data.first()[enabledKey(integrationId)] ?: false

    override suspend fun setUserEnabled(integrationId: String, enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[enabledKey(integrationId)] = enabled
            if (enabled) {
                prefs[everEnabledKey(integrationId)] = true
            }
        }
    }

    override fun observeUserEnabled(integrationId: String): Flow<Boolean> =
        dataStore.data.map { prefs ->
            prefs[enabledKey(integrationId)] ?: false
        }

    override suspend fun wasEverEnabled(integrationId: String): Boolean =
        dataStore.data.first()[everEnabledKey(integrationId)] ?: false

    override fun observeEverEnabled(integrationId: String): Flow<Boolean> =
        dataStore.data.map { prefs ->
            prefs[everEnabledKey(integrationId)] ?: false
        }

    override fun observeChanges(): Flow<Unit> = dataStore.data.map { }

    companion object {
        private fun enabledKey(id: String) = booleanPreferencesKey("enabled_$id")
        private fun everEnabledKey(id: String) = booleanPreferencesKey("ever_enabled_$id")
    }
}
