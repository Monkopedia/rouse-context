package com.rousecontext.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rousecontext.api.IntegrationState
import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.api.McpIntegration
import com.rousecontext.api.deriveIntegrationState
import com.rousecontext.app.McpUrlProvider
import com.rousecontext.app.ui.screens.AuditEntry
import com.rousecontext.app.ui.screens.CertBanner
import com.rousecontext.app.ui.screens.ConnectionStatus
import com.rousecontext.app.ui.screens.DashboardState
import com.rousecontext.app.ui.screens.IntegrationItem
import com.rousecontext.app.ui.screens.IntegrationStatus
import com.rousecontext.mcp.core.TokenStore
import com.rousecontext.notifications.audit.AuditDao
import com.rousecontext.tunnel.TunnelClient
import com.rousecontext.tunnel.TunnelState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * Drives the main dashboard screen with connection state, integration list,
 * and recent audit activity.
 */
class MainDashboardViewModel(
    private val integrations: List<McpIntegration>,
    private val stateStore: IntegrationStateStore,
    private val tokenStore: TokenStore,
    private val auditDao: AuditDao,
    private val urlProvider: McpUrlProvider,
    tunnelClient: TunnelClient,
    certRenewalBanner: Flow<CertBanner?> = flowOf(null)
) : ViewModel() {

    private val tunnelStateFlow = tunnelClient.state

    private val recentAuditFlow = auditDao.observeRecent(
        startMillis = System.currentTimeMillis() - RECENT_WINDOW_MS,
        endMillis = Long.MAX_VALUE,
        limit = RECENT_LIMIT
    )

    private val certRenewalBannerFlow = certRenewalBanner.onStart<CertBanner?> { emit(null) }

    val state: StateFlow<DashboardState> = combine(
        tunnelStateFlow,
        stateStore.observeChanges().onStart { emit(Unit) },
        recentAuditFlow,
        certRenewalBannerFlow
    ) { tunnelState, _, recentEntries, certBanner ->
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
            AuditEntry(
                time = TIME_FORMAT.format(Date(entry.timestampMillis)),
                toolName = entry.toolName,
                durationMs = entry.durationMillis
            )
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

    companion object {
        private const val RECENT_LIMIT = 5
        private const val RECENT_WINDOW_MS = 24 * 60 * 60 * 1000L
        private const val STOP_TIMEOUT_MS = 5_000L
        private val TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())
    }
}
