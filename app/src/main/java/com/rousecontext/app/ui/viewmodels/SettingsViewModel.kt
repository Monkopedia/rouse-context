package com.rousecontext.app.ui.viewmodels

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rousecontext.api.McpIntegration
import com.rousecontext.api.NotificationSettingsProvider
import com.rousecontext.api.PostSessionMode
import com.rousecontext.app.RouseApplication
import com.rousecontext.app.state.ThemeMode
import com.rousecontext.app.state.ThemePreference
import com.rousecontext.app.ui.screens.SettingsState
import com.rousecontext.app.ui.screens.TrustOverallStatus
import com.rousecontext.app.ui.screens.TrustStatusState
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.RelayApiClient
import com.rousecontext.tunnel.RelayApiResult
import com.rousecontext.tunnel.SecretGenerator
import com.rousecontext.work.SecurityCheckWorker
import com.rousecontext.work.SharedPreferencesSpuriousWakeRecorder
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Snapshot of spurious-wake telemetry surfaced in Settings.
 *
 * @property rolling24h count of spurious wakes within the last 24 hours
 * @property total lifetime count of all completed wake cycles
 */
data class SpuriousWakeStats(val rolling24h: Int, val total: Long) {
    companion object {
        val EMPTY = SpuriousWakeStats(rolling24h = 0, total = 0L)
    }
}

/**
 * Reads and writes settings: idle timeout, notification mode,
 * battery optimization status, trust status from security checks.
 */
class SettingsViewModel(
    private val notificationSettingsProvider: NotificationSettingsProvider,
    private val themePreference: ThemePreference,
    private val relayApiClient: RelayApiClient,
    private val certStore: CertificateStore,
    private val integrations: List<McpIntegration> = emptyList(),
    private val securityCheckPrefs: SharedPreferences? = null,
    private val settingsPrefs: SharedPreferences? = null,
    spuriousWakesFlow: Flow<SpuriousWakeStats> = flowOf(SpuriousWakeStats.EMPTY)
) : ViewModel() {

    private val refreshTrigger = MutableStateFlow(0)
    private val rotateInProgress = MutableStateFlow(false)
    private val rotateError = MutableStateFlow<String?>(null)

    val state: StateFlow<SettingsState> = combine(
        refreshTrigger,
        themePreference.themeMode,
        rotateInProgress,
        rotateError,
        spuriousWakesFlow
    ) { _, themeMode, rotating, rotateErr, spurious ->
        val settings = notificationSettingsProvider.settings
        val intervalHours = settingsPrefs?.getInt(
            RouseApplication.KEY_SECURITY_CHECK_INTERVAL_HOURS,
            RouseApplication.DEFAULT_INTERVAL_HOURS
        ) ?: RouseApplication.DEFAULT_INTERVAL_HOURS
        SettingsState(
            postSessionMode = settings.postSessionMode.toDisplayString(),
            themeMode = themeMode.toDisplayString(),
            securityCheckInterval = "$intervalHours hours",
            trustStatus = readTrustStatus(),
            canRotateAddress = !rotating,
            rotationCooldownMessage = rotateErr,
            spuriousWakesLast24h = spurious.rolling24h,
            totalWakesLifetime = spurious.total,
            isLoading = false,
            errorMessage = null
        )
    }.catch { cause ->
        emit(
            SettingsState(
                isLoading = false,
                errorMessage = cause.message ?: "Could not load settings."
            )
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = SettingsState(isLoading = true)
    )

    /**
     * Toggle state for "Show all MCP messages in audit history". Backed by
     * [NotificationSettingsProvider.observeSettings] so reads reflect any
     * concurrent write (e.g. from the Audit tab).
     */
    val showAllMcpMessages: StateFlow<Boolean> = notificationSettingsProvider
        .observeSettings()
        .map { it.showAllMcpMessages }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = false
        )

    /** Persist the show-all-MCP-messages toggle. */
    fun setShowAllMcpMessages(value: Boolean) {
        viewModelScope.launch {
            notificationSettingsProvider.setShowAllMcpMessages(value)
        }
    }

    fun setIdleTimeout(minutes: Int) {
        // TODO: persist to DataStore when idle timeout DataStore is added
        refresh()
    }

    fun setPostSessionMode(mode: String) {
        val parsed = when (mode) {
            "Summary" -> PostSessionMode.SUMMARY
            "Each usage" -> PostSessionMode.EACH_USAGE
            "Suppress" -> PostSessionMode.SUPPRESS
            else -> return
        }
        viewModelScope.launch {
            notificationSettingsProvider.setPostSessionMode(parsed)
            refresh()
        }
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

    fun setSecurityCheckInterval(interval: String) {
        val hours = interval.replace(" hours", "").toIntOrNull()
            ?: RouseApplication.DEFAULT_INTERVAL_HOURS
        settingsPrefs?.edit()?.putInt(
            RouseApplication.KEY_SECURITY_CHECK_INTERVAL_HOURS,
            hours
        )?.apply()
        refresh()
    }

    fun rotateSecret() {
        viewModelScope.launch {
            rotateInProgress.value = true
            rotateError.value = null

            val subdomain = certStore.getSubdomain()
            if (subdomain == null) {
                rotateError.value = "Device not registered"
                rotateInProgress.value = false
                return@launch
            }
            val integrationIds = integrations.map { it.id }
            val newSecrets = SecretGenerator.generateAll(integrationIds)
            val validSecrets = newSecrets.values.toList()

            when (val result = relayApiClient.updateSecrets(subdomain, validSecrets)) {
                is RelayApiResult.Success -> {
                    certStore.storeIntegrationSecrets(newSecrets)
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

    fun acknowledgeAlert() {
        securityCheckPrefs?.edit()
            ?.putString(SecurityCheckWorker.KEY_SELF_CERT_RESULT, "")
            ?.putString(SecurityCheckWorker.KEY_CT_LOG_RESULT, "")
            ?.putLong(SecurityCheckWorker.KEY_LAST_CHECK_TIME, 0L)
            ?.apply()
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

        /**
         * Build a flow that tracks spurious-wake stats written by
         * [com.rousecontext.work.SharedPreferencesSpuriousWakeRecorder]. Emits
         * on first collection and whenever the recorder prefs change.
         */
        fun spuriousWakeStatsFlow(
            prefs: SharedPreferences,
            clock: () -> Long = { System.currentTimeMillis() }
        ): Flow<SpuriousWakeStats> = callbackFlow {
            fun emitCurrent() {
                val total = prefs.getLong(
                    SharedPreferencesSpuriousWakeRecorder.KEY_TOTAL,
                    0L
                )
                val tsJson = prefs.getString(
                    SharedPreferencesSpuriousWakeRecorder.KEY_SPURIOUS_TIMESTAMPS,
                    null
                )
                val rolling = SharedPreferencesSpuriousWakeRecorder.countWithinWindow(
                    tsJson,
                    clock()
                )
                trySend(SpuriousWakeStats(rolling24h = rolling, total = total))
            }

            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == SharedPreferencesSpuriousWakeRecorder.KEY_TOTAL ||
                    key == SharedPreferencesSpuriousWakeRecorder.KEY_SPURIOUS_TIMESTAMPS
                ) {
                    emitCurrent()
                }
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            emitCurrent()
            awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }

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
