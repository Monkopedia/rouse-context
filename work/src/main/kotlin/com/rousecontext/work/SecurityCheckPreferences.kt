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

private val Context.securityCheckDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "security_check")

/**
 * Typed DataStore accessor for security-check state written by
 * [SecurityCheckWorker] and read by the settings surface and the opportunistic
 * re-check trigger in [TunnelForegroundService].
 *
 * Replaces the previous SharedPreferences backing (see issue #116) so reads
 * and writes happen entirely from suspend contexts without `runBlocking`.
 */
class SecurityCheckPreferences(private val context: Context) {

    private val dataStore get() = context.securityCheckDataStore

    suspend fun lastCheckAt(): Long = dataStore.data.first()[KEY_LAST_CHECK_TIME] ?: 0L

    fun observeLastCheckAt(): Flow<Long> = dataStore.data.map { it[KEY_LAST_CHECK_TIME] ?: 0L }

    suspend fun selfCertResult(): String = dataStore.data.first()[KEY_SELF_CERT_RESULT] ?: ""

    fun observeSelfCertResult(): Flow<String> =
        dataStore.data.map { it[KEY_SELF_CERT_RESULT] ?: "" }

    suspend fun ctLogResult(): String = dataStore.data.first()[KEY_CT_LOG_RESULT] ?: ""

    fun observeCtLogResult(): Flow<String> = dataStore.data.map { it[KEY_CT_LOG_RESULT] ?: "" }

    suspend fun certFingerprint(): String = dataStore.data.first()[KEY_CERT_FINGERPRINT] ?: ""

    fun observeCertFingerprint(): Flow<String> =
        dataStore.data.map { it[KEY_CERT_FINGERPRINT] ?: "" }

    suspend fun recordCheck(lastCheckAt: Long, selfCertResult: String, ctLogResult: String) {
        dataStore.edit { prefs ->
            prefs[KEY_LAST_CHECK_TIME] = lastCheckAt
            prefs[KEY_SELF_CERT_RESULT] = selfCertResult
            prefs[KEY_CT_LOG_RESULT] = ctLogResult
        }
    }

    suspend fun setCertFingerprint(value: String) {
        dataStore.edit { prefs ->
            prefs[KEY_CERT_FINGERPRINT] = value
        }
    }

    /** Clears the acknowledgeable alert/warning fields (used by "Acknowledge"). */
    suspend fun clearResults() {
        dataStore.edit { prefs ->
            prefs[KEY_SELF_CERT_RESULT] = ""
            prefs[KEY_CT_LOG_RESULT] = ""
            prefs[KEY_LAST_CHECK_TIME] = 0L
        }
    }

    companion object {
        private val KEY_LAST_CHECK_TIME = longPreferencesKey("last_check_time")
        private val KEY_SELF_CERT_RESULT = stringPreferencesKey("self_cert_result")
        private val KEY_CT_LOG_RESULT = stringPreferencesKey("ct_log_result")
        private val KEY_CERT_FINGERPRINT = stringPreferencesKey("cert_fingerprint")
    }
}
