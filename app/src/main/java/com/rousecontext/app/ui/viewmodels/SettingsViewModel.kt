package com.rousecontext.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rousecontext.api.McpIntegration
import com.rousecontext.api.NotificationSettingsProvider
import com.rousecontext.api.PostSessionMode
import com.rousecontext.app.state.AppStatePreferences
import com.rousecontext.app.state.ThemeMode
import com.rousecontext.app.state.ThemePreference
import com.rousecontext.app.ui.screens.PostSessionModeOption
import com.rousecontext.app.ui.screens.SecurityCheckIntervalOption
import com.rousecontext.app.ui.screens.SettingsState
import com.rousecontext.app.ui.screens.ThemeModeOption
import com.rousecontext.app.ui.screens.TrustOverallStatus
import com.rousecontext.app.ui.screens.TrustStatusState
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.RelayApiClient
import com.rousecontext.tunnel.RelayApiResult
import com.rousecontext.work.SecurityCheckPreferences
import com.rousecontext.work.SpuriousWakePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
private data class Quint<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)

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
    private val securityCheckPreferences: SecurityCheckPreferences? = null,
    private val appStatePreferences: AppStatePreferences? = null,
    spuriousWakesFlow: Flow<SpuriousWakeStats> = flowOf(SpuriousWakeStats.EMPTY)
) : ViewModel() {

    private val refreshTrigger = MutableStateFlow(0)
    private val rotateInProgress = MutableStateFlow(false)
    private val rotateError = MutableStateFlow<String?>(null)

    /**
     * Reactive trust status: recomputed whenever the underlying
     * [SecurityCheckPreferences] values change. Falls back to a single emission
     * of `null` when the accessor is absent (tests only).
     */
    private val trustStatusFlow: Flow<TrustStatusState?> =
        securityCheckPreferences?.let { prefs ->
            combine(
                prefs.observeLastCheckAt(),
                prefs.observeSelfCertResult(),
                prefs.observeCtLogResult(),
                prefs.observeCertFingerprint()
            ) { lastCheck, self, ct, fingerprint ->
                if (lastCheck == 0L) {
                    null
                } else {
                    TrustStatusState(
                        lastCheckTime = lastCheck,
                        selfCheckResult = self,
                        ctCheckResult = ct,
                        certFingerprint = fingerprint,
                        overallStatus = computeOverallStatus(self, ct)
                    )
                }
            }
        } ?: flowOf(null)

    private val intervalFlow: Flow<Int> = appStatePreferences
        ?.observeSecurityCheckIntervalHours()
        ?: flowOf(AppStatePreferences.DEFAULT_INTERVAL_HOURS)

    val state: StateFlow<SettingsState> = combine(
        combine(
            refreshTrigger,
            themePreference.themeMode,
            rotateInProgress,
            rotateError,
            spuriousWakesFlow
        ) { _, themeMode, rotating, rotateErr, spurious ->
            Quint(themeMode, rotating, rotateErr, spurious, Unit)
        },
        notificationSettingsProvider.observeSettings(),
        trustStatusFlow,
        intervalFlow
    ) { tuple, settings, trust, intervalHours ->
        val themeMode = tuple.a
        val rotating = tuple.b
        val rotateErr = tuple.c
        val spurious = tuple.d
        SettingsState(
            postSessionMode = settings.postSessionMode.toOption(),
            themeMode = themeMode.toOption(),
            securityCheckInterval = SecurityCheckIntervalOption.forHours(intervalHours),
            trustStatus = trust,
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

    fun setPostSessionMode(mode: PostSessionModeOption) {
        viewModelScope.launch {
            notificationSettingsProvider.setPostSessionMode(mode.toDomain())
            refresh()
        }
    }

    fun setThemeMode(mode: ThemeModeOption) {
        viewModelScope.launch {
            themePreference.setThemeMode(mode.toDomain())
        }
    }

    fun setSecurityCheckInterval(interval: SecurityCheckIntervalOption) {
        viewModelScope.launch {
            appStatePreferences?.setSecurityCheckIntervalHours(interval.hours)
            refresh()
        }
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

            when (val result = relayApiClient.updateSecrets(subdomain, integrationIds)) {
                is RelayApiResult.Success -> {
                    certStore.storeIntegrationSecrets(result.data.secrets)
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
        viewModelScope.launch {
            securityCheckPreferences?.clearResults()
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            refreshTrigger.value++
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        /**
         * Build a flow that tracks spurious-wake stats written by
         * [com.rousecontext.work.DataStoreSpuriousWakeRecorder]. Emits on
         * first collection and whenever the underlying DataStore changes.
         */
        fun spuriousWakeStatsFlow(
            preferences: SpuriousWakePreferences,
            clock: () -> Long = { System.currentTimeMillis() }
        ): Flow<SpuriousWakeStats> = preferences.observeCounters().map { counters ->
            val rolling = SpuriousWakePreferences.countWithinWindow(
                counters.serializedTimestamps,
                clock()
            )
            SpuriousWakeStats(rolling24h = rolling, total = counters.total)
        }

        private fun PostSessionMode.toOption(): PostSessionModeOption = when (this) {
            PostSessionMode.SUMMARY -> PostSessionModeOption.SUMMARY
            PostSessionMode.EACH_USAGE -> PostSessionModeOption.EACH_USAGE
            PostSessionMode.SUPPRESS -> PostSessionModeOption.SUPPRESS
        }

        private fun PostSessionModeOption.toDomain(): PostSessionMode = when (this) {
            PostSessionModeOption.SUMMARY -> PostSessionMode.SUMMARY
            PostSessionModeOption.EACH_USAGE -> PostSessionMode.EACH_USAGE
            PostSessionModeOption.SUPPRESS -> PostSessionMode.SUPPRESS
        }

        private fun ThemeMode.toOption(): ThemeModeOption = when (this) {
            ThemeMode.LIGHT -> ThemeModeOption.LIGHT
            ThemeMode.DARK -> ThemeModeOption.DARK
            ThemeMode.AUTO -> ThemeModeOption.AUTO
        }

        private fun ThemeModeOption.toDomain(): ThemeMode = when (this) {
            ThemeModeOption.LIGHT -> ThemeMode.LIGHT
            ThemeModeOption.DARK -> ThemeMode.DARK
            ThemeModeOption.AUTO -> ThemeMode.AUTO
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
