package com.rousecontext.app.ui.viewmodels

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rousecontext.api.NotificationSettingsProvider
import com.rousecontext.api.PostSessionMode
import com.rousecontext.app.ui.screens.SettingsState
import com.rousecontext.app.ui.screens.TrustOverallStatus
import com.rousecontext.app.ui.screens.TrustStatusState
import com.rousecontext.work.SecurityCheckWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Reads and writes settings: idle timeout, notification mode,
 * battery optimization status, trust status from security checks.
 */
class SettingsViewModel(
    private val notificationSettingsProvider: NotificationSettingsProvider,
    private val securityCheckPrefs: SharedPreferences? = null
) : ViewModel() {

    private val refreshTrigger = MutableStateFlow(0)

    val state: StateFlow<SettingsState> = refreshTrigger
        .map {
            val settings = notificationSettingsProvider.settings
            SettingsState(
                postSessionMode = settings.postSessionMode.toDisplayString(),
                trustStatus = readTrustStatus()
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = SettingsState()
        )

    fun setIdleTimeout(minutes: Int) {
        // TODO: persist to DataStore when idle timeout DataStore is added
        refresh()
    }

    fun setPostSessionMode(mode: String) {
        // TODO: persist to notification settings DataStore
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            refreshTrigger.value++
        }
    }

    private fun readTrustStatus(): TrustStatusState? {
        val prefs = securityCheckPrefs ?: return null
        val lastCheckTime = prefs.getLong(SecurityCheckWorker.KEY_LAST_CHECK_TIME, 0L)
        if (lastCheckTime == 0L) return null

        val selfResult = prefs.getString(SecurityCheckWorker.KEY_SELF_CERT_RESULT, "") ?: ""
        val ctResult = prefs.getString(SecurityCheckWorker.KEY_CT_LOG_RESULT, "") ?: ""
        val fingerprint = prefs.getString(KEY_CERT_FINGERPRINT, "") ?: ""

        val overallStatus = computeOverallStatus(selfResult, ctResult)

        return TrustStatusState(
            lastCheckTime = lastCheckTime,
            selfCheckResult = selfResult,
            ctCheckResult = ctResult,
            certFingerprint = fingerprint,
            overallStatus = overallStatus
        )
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        const val KEY_CERT_FINGERPRINT = "cert_fingerprint"

        private fun PostSessionMode.toDisplayString(): String = when (this) {
            PostSessionMode.SUMMARY -> "Summary"
            PostSessionMode.EACH_USAGE -> "Each usage"
            PostSessionMode.SUPPRESS -> "Suppress"
        }

        internal fun computeOverallStatus(
            selfResult: String,
            ctResult: String
        ): TrustOverallStatus = when {
            selfResult == "alert" || ctResult == "alert" -> TrustOverallStatus.ALERT
            selfResult == "warning" || ctResult == "warning" -> TrustOverallStatus.WARNING
            else -> TrustOverallStatus.VERIFIED
        }
    }
}
