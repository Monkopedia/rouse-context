package com.rousecontext.app.ui.viewmodels

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rousecontext.api.NotificationSettingsProvider
import com.rousecontext.api.PostSessionMode
import com.rousecontext.app.state.ThemeMode
import com.rousecontext.app.state.ThemePreference
import com.rousecontext.app.ui.screens.SettingsState
import com.rousecontext.app.ui.screens.TrustOverallStatus
import com.rousecontext.app.ui.screens.TrustStatusState
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.RelayApiClient
import com.rousecontext.tunnel.RelayApiResult
import com.rousecontext.work.SecurityCheckWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Reads and writes settings: idle timeout, notification mode,
 * battery optimization status, trust status from security checks.
 */
class SettingsViewModel(
    private val notificationSettingsProvider: NotificationSettingsProvider,
    private val themePreference: ThemePreference,
    private val relayApiClient: RelayApiClient,
    private val certStore: CertificateStore,
    private val securityCheckPrefs: SharedPreferences? = null
) : ViewModel() {

    private val refreshTrigger = MutableStateFlow(0)
    private val rotateInProgress = MutableStateFlow(false)
    private val rotateError = MutableStateFlow<String?>(null)

    val state: StateFlow<SettingsState> = combine(
        refreshTrigger,
        themePreference.themeMode,
        rotateInProgress,
        rotateError
    ) { _, themeMode, rotating, rotateErr ->
        val settings = notificationSettingsProvider.settings
        SettingsState(
            postSessionMode = settings.postSessionMode.toDisplayString(),
            themeMode = themeMode.toDisplayString(),
            trustStatus = readTrustStatus(),
            canRotateAddress = !rotating,
            rotationCooldownMessage = rotateErr
        )
    }.stateIn(
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

    fun setThemeMode(mode: String) {
        val themeMode = when (mode) {
            "Light" -> ThemeMode.LIGHT
            "Dark" -> ThemeMode.DARK
            else -> ThemeMode.AUTO
        }
        viewModelScope.launch {
            themePreference.setThemeMode(themeMode)
        }
    }

    fun rotateSecret() {
        viewModelScope.launch {
            rotateInProgress.value = true
            rotateError.value = null
            when (val result = relayApiClient.rotateSecret()) {
                is RelayApiResult.Success -> {
                    certStore.storeSecretPrefix(result.data.secretPrefix)
                }
                is RelayApiResult.RateLimited -> {
                    rotateError.value = "Rate limited. Try again later."
                }
                is RelayApiResult.Error -> {
                    rotateError.value = "Failed: ${result.message}"
                }
                is RelayApiResult.NetworkError -> {
                    rotateError.value = "Network error"
                }
            }
            rotateInProgress.value = false
        }
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

        private fun ThemeMode.toDisplayString(): String = when (this) {
            ThemeMode.LIGHT -> "Light"
            ThemeMode.DARK -> "Dark"
            ThemeMode.AUTO -> "Auto"
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
