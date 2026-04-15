package com.rousecontext.work

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.certRenewalDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "cert_renewal")

/**
 * Typed DataStore accessor for [CertRenewalWorker] outcome telemetry.
 *
 * The worker writes an outcome per run; the dashboard's
 * [com.rousecontext.app.cert.certRenewalBannerFlow] observes terminal outcomes
 * to surface a banner.
 */
class CertRenewalPreferences(private val context: Context) {

    private val dataStore get() = context.certRenewalDataStore

    suspend fun lastAttemptAt(): Long = dataStore.data.first()[KEY_LAST_ATTEMPT_TIME] ?: 0L

    fun observeLastAttemptAt(): Flow<Long> = dataStore.data.map { it[KEY_LAST_ATTEMPT_TIME] ?: 0L }

    suspend fun lastOutcome(): String? = dataStore.data.first()[KEY_LAST_OUTCOME]

    fun observeLastOutcome(): Flow<String?> = dataStore.data.map { it[KEY_LAST_OUTCOME] }

    suspend fun recordAttempt(attemptAt: Long, outcome: String) {
        dataStore.edit { prefs ->
            prefs[KEY_LAST_ATTEMPT_TIME] = attemptAt
            prefs[KEY_LAST_OUTCOME] = outcome
        }
    }

    companion object {
        private val KEY_LAST_ATTEMPT_TIME = longPreferencesKey("last_attempt_time")
        private val KEY_LAST_OUTCOME = stringPreferencesKey("last_outcome")
    }
}
