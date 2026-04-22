package com.rousecontext.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rousecontext.api.IntegrationState
import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.api.McpIntegration
import com.rousecontext.api.deriveIntegrationState
import com.rousecontext.app.McpUrlProvider
import com.rousecontext.app.ui.screens.CertBanner
import com.rousecontext.app.ui.screens.ConnectionStatus
import com.rousecontext.app.ui.screens.DashboardState
import com.rousecontext.app.ui.screens.IntegrationItem
import com.rousecontext.app.ui.screens.IntegrationStatus
import com.rousecontext.app.ui.screens.NotificationBanner
import com.rousecontext.app.ui.screens.SpuriousWakeBanner
import com.rousecontext.mcp.core.TokenStore
import com.rousecontext.notifications.audit.AuditDao
import com.rousecontext.tunnel.TunnelClient
import com.rousecontext.tunnel.TunnelState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * Drives the main dashboard screen with connection state, integration list,
 * and recent audit activity.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LongParameterList")
class MainDashboardViewModel(
    private val integrations: List<McpIntegration>,
    private val stateStore: IntegrationStateStore,
    private val tokenStore: TokenStore,
    private val auditDao: AuditDao,
    private val urlProvider: McpUrlProvider,
    tunnelClient: TunnelClient,
    certRenewalBanner: Flow<CertBanner?> = flowOf(null),
    notificationsEnabled: Flow<Boolean> = flowOf(true),
    spuriousWakesFlow: Flow<SpuriousWakeStats> = flowOf(SpuriousWakeStats.EMPTY),
    /**
     * Ticker driving the rolling-24h "Recent Activity" cutoff. Each emission
     * is a fresh wall-clock timestamp that `flatMapLatest` uses to restart
     * the DAO query with a moved-forward lower bound. Fixes #370: the
     * previous implementation captured the cutoff once at VM init, pinning
     * the window to the subscription time for the entire dashboard
     * lifetime.
     *
     * Default is a 60 s poll (see [defaultCutoffTicker]) — delayed inline
     * so collection terminates cleanly when the upstream is cancelled.
     * Tests pass a [kotlinx.coroutines.flow.MutableStateFlow] or similar
     * so the cutoff can be advanced deterministically without leaking a
     * `while(true) { delay() }` past the test's lifetime.
     */
    cutoffTicker: Flow<Long> = defaultCutoffTicker()
) : ViewModel() {

    private val tunnelStateFlow = tunnelClient.state

    private val recentAuditFlow = cutoffTicker.distinctUntilChanged().flatMapLatest { now ->
        auditDao.observeRecent(
            startMillis = now - RECENT_WINDOW_MS,
            endMillis = Long.MAX_VALUE,
            limit = RECENT_LIMIT
        )
    }

    private val certRenewalBannerFlow = certRenewalBanner.onStart<CertBanner?> { emit(null) }
    private val notificationsEnabledFlow = notificationsEnabled.onStart { emit(true) }
    private val spuriousWakesFlowStarted = spuriousWakesFlow.onStart {
        emit(SpuriousWakeStats.EMPTY)
    }

    val state: StateFlow<DashboardState> = combine(
        combine(
            tunnelStateFlow,
            stateStore.observeChanges().onStart { emit(Unit) },
            recentAuditFlow,
            certRenewalBannerFlow,
            notificationsEnabledFlow
        ) { tunnelState, _, recentEntries, certBanner, notifsEnabled ->
            BannerInputs(tunnelState, recentEntries, certBanner, notifsEnabled)
        },
        spuriousWakesFlowStarted
    ) { inputs, spurious ->
        val tunnelState = inputs.tunnelState
        val recentEntries = inputs.recentEntries
        val certBanner = inputs.certBanner
        val notifsEnabled = inputs.notifsEnabled
        val connection = when (tunnelState) {
            TunnelState.CONNECTED, TunnelState.ACTIVE -> ConnectionStatus.CONNECTED
            else -> ConnectionStatus.DISCONNECTED
        }
        val sessionCount = if (tunnelState == TunnelState.ACTIVE) 1 else 0
        val items = integrations.mapNotNull { integration ->
            val derived = deriveIntegrationState(
                userEnabled = stateStore.isUserEnabled(integration.id),
                wasEverEnabled = stateStore.wasEverEnabled(integration.id),
                isAvailable = integration.isAvailable(),
                hasTokens = tokenStore.hasTokens(integration.id)
            )
            if (derived == IntegrationState.Active || derived == IntegrationState.Pending) {
                IntegrationItem(
                    id = integration.id,
                    name = integration.displayName,
                    status = if (derived == IntegrationState.Active) {
                        IntegrationStatus.ACTIVE
                    } else {
                        IntegrationStatus.PENDING
                    },
                    url = urlProvider.buildUrl(integration.id) ?: ""
                )
            } else {
                null
            }
        }

        val recent = recentEntries.map { entry ->
            AuditHistoryViewModel.toHistoryEntry(entry)
        }

        val hasMoreToAdd = integrations.any { integration ->
            val derived = deriveIntegrationState(
                userEnabled = stateStore.isUserEnabled(integration.id),
                wasEverEnabled = stateStore.wasEverEnabled(integration.id),
                isAvailable = integration.isAvailable(),
                hasTokens = tokenStore.hasTokens(integration.id)
            )
            derived != IntegrationState.Active && derived != IntegrationState.Pending
        }

        DashboardState(
            connectionStatus = connection,
            activeSessionCount = sessionCount,
            integrations = items,
            recentActivity = recent,
            certBanner = certBanner,
            notificationBanner = if (notifsEnabled) null else NotificationBanner,
            spuriousWakeBanner = if (spurious.rolling24h > SPURIOUS_WAKE_BANNER_THRESHOLD) {
                SpuriousWakeBanner(rolling24hCount = spurious.rolling24h)
            } else {
                null
            },
            hasMoreIntegrationsToAdd = hasMoreToAdd,
            isLoading = false,
            errorMessage = null
        )
    }.catch { cause ->
        emit(
            DashboardState(
                isLoading = false,
                errorMessage = cause.message ?: "Something went wrong."
            )
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = DashboardState(isLoading = true)
    )

    /**
     * Trigger a retry after an error state. The underlying flows are reactive, so
     * re-collection happens automatically when subscribers re-attach. This just
     * resets the internal error state so the UI shows loading while flows re-emit.
     */
    fun retry() {
        // No-op: collection resumes when state flow is re-subscribed. Re-emission
        // on the underlying sources will clear the error naturally.
    }

    /**
     * Manual refresh hook for data that isn't reactive.
     * Integration and audit data are reactive and refresh automatically.
     */
    fun refresh() {
        // Currently a no-op; kept for future non-reactive state.
    }

    private data class BannerInputs(
        val tunnelState: TunnelState,
        val recentEntries: List<com.rousecontext.notifications.audit.AuditEntry>,
        val certBanner: CertBanner?,
        val notifsEnabled: Boolean
    )

    companion object {
        private const val RECENT_LIMIT = 5
        private const val RECENT_WINDOW_MS = 24 * 60 * 60 * 1000L
        private const val STOP_TIMEOUT_MS = 5_000L

        /**
         * How often the rolling-24h cutoff for the "Recent Activity" teaser
         * is recomputed. One minute is well below the granularity a user
         * would notice and keeps the dashboard flow from restarting more
         * than necessary.
         */
        private const val CUTOFF_REFRESH_INTERVAL_MS = 60_000L

        /**
         * Rolling-24h spurious-wake threshold above which the informational
         * dashboard banner appears.
         */
        private const val SPURIOUS_WAKE_BANNER_THRESHOLD = 10

        /**
         * Production ticker: emits [System.currentTimeMillis] once, waits
         * [CUTOFF_REFRESH_INTERVAL_MS], repeats. `delay` is cancellable so
         * the loop terminates when the upstream is cancelled; the flow
         * therefore does not leak past the VM's viewModelScope.
         */
        private fun defaultCutoffTicker(): Flow<Long> = flow {
            while (true) {
                emit(System.currentTimeMillis())
                delay(CUTOFF_REFRESH_INTERVAL_MS)
            }
        }
    }
}
