package com.rousecontext.work

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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

    /**
     * Issue #256: per-source consecutive-warning counter used by
     * [SecurityCheckWorker] to debounce notification noise. A notification
     * only fires after the same [sourceName] returns [SecurityCheckResult.Warning]
     * on [SecurityCheckWorker.WARNING_NOTIFICATION_THRESHOLD] consecutive runs.
     * Any non-Warning result resets the counter via [resetWarningStreak].
     */
    suspend fun warningStreak(sourceName: String): Int =
        dataStore.data.first()[warningStreakKey(sourceName)] ?: 0

    suspend fun incrementWarningStreak(sourceName: String): Int {
        val key = warningStreakKey(sourceName)
        var newValue = 0
        dataStore.edit { prefs ->
            newValue = (prefs[key] ?: 0) + 1
            prefs[key] = newValue
        }
        return newValue
    }

    suspend fun resetWarningStreak(sourceName: String) {
        dataStore.edit { prefs ->
            prefs[warningStreakKey(sourceName)] = 0
        }
    }

    /** Clears the acknowledgeable alert/warning fields (used by "Acknowledge"). */
    suspend fun clearResults() {
        dataStore.edit { prefs ->
            prefs[KEY_SELF_CERT_RESULT] = ""
            prefs[KEY_CT_LOG_RESULT] = ""
            prefs[KEY_LAST_CHECK_TIME] = 0L
            prefs[warningStreakKey(SOURCE_SELF_CERT)] = 0
            prefs[warningStreakKey(SOURCE_CT_LOG)] = 0
        }
    }

    companion object {
        /**
         * Stable source names used as DataStore key suffixes. Must stay in sync
         * with the sources passed in by [SecurityCheckWorker].
         */
        const val SOURCE_SELF_CERT = "self_cert"
        const val SOURCE_CT_LOG = "ct_log"

        private val KEY_LAST_CHECK_TIME = longPreferencesKey("last_check_time")
        private val KEY_SELF_CERT_RESULT = stringPreferencesKey("self_cert_result")
        private val KEY_CT_LOG_RESULT = stringPreferencesKey("ct_log_result")
        private val KEY_CERT_FINGERPRINT = stringPreferencesKey("cert_fingerprint")

        private fun warningStreakKey(sourceName: String) =
            intPreferencesKey("warning_streak_$sourceName")
    }
}
