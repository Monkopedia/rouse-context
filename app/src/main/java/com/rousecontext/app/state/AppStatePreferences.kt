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

private val Context.appStateDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "app_state")

/**
 * Typed DataStore accessor for app-level state: first-launch marker,
 * security-check schedule knobs. Replaces the legacy `rouse_settings`
 * SharedPreferences file (see issue #116).
 */
class AppStatePreferences(private val context: Context) {

    private val dataStore get() = context.appStateDataStore

    suspend fun securityCheckIntervalHours(): Int =
        dataStore.data.first()[KEY_SECURITY_CHECK_INTERVAL_HOURS]
            ?: DEFAULT_INTERVAL_HOURS

    fun observeSecurityCheckIntervalHours(): Flow<Int> = dataStore.data.map {
        it[KEY_SECURITY_CHECK_INTERVAL_HOURS] ?: DEFAULT_INTERVAL_HOURS
    }

    suspend fun setSecurityCheckIntervalHours(value: Int) {
        dataStore.edit { prefs ->
            prefs[KEY_SECURITY_CHECK_INTERVAL_HOURS] = value
        }
    }

    suspend fun hasLaunchedBefore(): Boolean =
        dataStore.data.first()[KEY_HAS_LAUNCHED_BEFORE] ?: false

    suspend fun markLaunched() {
        dataStore.edit { prefs ->
            prefs[KEY_HAS_LAUNCHED_BEFORE] = true
        }
    }

    /**
     * Test-only helper: restores initial state. Not referenced from production code.
     */
    internal suspend fun reset() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_SECURITY_CHECK_INTERVAL_HOURS)
            prefs.remove(KEY_HAS_LAUNCHED_BEFORE)
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_HOURS = 12

        private val KEY_SECURITY_CHECK_INTERVAL_HOURS =
            intPreferencesKey("security_check_interval_hours")
        private val KEY_HAS_LAUNCHED_BEFORE = booleanPreferencesKey("has_launched_before")
    }
}
