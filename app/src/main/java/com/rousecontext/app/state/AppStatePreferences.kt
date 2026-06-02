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

    suspend fun idleTimeoutMinutes(): Int =
        dataStore.data.first()[KEY_IDLE_TIMEOUT_MINUTES] ?: DEFAULT_IDLE_TIMEOUT_MINUTES

    fun observeIdleTimeoutMinutes(): Flow<Int> = dataStore.data.map {
        it[KEY_IDLE_TIMEOUT_MINUTES] ?: DEFAULT_IDLE_TIMEOUT_MINUTES
    }

    suspend fun setIdleTimeoutMinutes(value: Int) {
        dataStore.edit { prefs ->
            prefs[KEY_IDLE_TIMEOUT_MINUTES] = value
        }
    }

    suspend fun idleTimeoutDisabled(): Boolean =
        dataStore.data.first()[KEY_IDLE_TIMEOUT_DISABLED] ?: false

    fun observeIdleTimeoutDisabled(): Flow<Boolean> = dataStore.data.map {
        it[KEY_IDLE_TIMEOUT_DISABLED] ?: false
    }

    suspend fun setIdleTimeoutDisabled(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_IDLE_TIMEOUT_DISABLED] = value
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
            prefs.remove(KEY_IDLE_TIMEOUT_MINUTES)
            prefs.remove(KEY_IDLE_TIMEOUT_DISABLED)
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_HOURS = 12

        /** Default idle timeout in minutes. Mirrors the historical `IDLE_TIMEOUT_MS` (5 min). */
        const val DEFAULT_IDLE_TIMEOUT_MINUTES = 5

        private val KEY_SECURITY_CHECK_INTERVAL_HOURS =
            intPreferencesKey("security_check_interval_hours")
        private val KEY_HAS_LAUNCHED_BEFORE = booleanPreferencesKey("has_launched_before")
        private val KEY_IDLE_TIMEOUT_MINUTES = intPreferencesKey("idle_timeout_minutes")
        private val KEY_IDLE_TIMEOUT_DISABLED = booleanPreferencesKey("idle_timeout_disabled")
    }
}
