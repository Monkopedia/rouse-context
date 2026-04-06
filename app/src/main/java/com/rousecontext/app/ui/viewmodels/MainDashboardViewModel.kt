package com.rousecontext.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rousecontext.api.IntegrationState
import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.api.McpIntegration
import com.rousecontext.api.deriveIntegrationState
import com.rousecontext.app.ui.screens.AuditEntry
import com.rousecontext.app.ui.screens.ConnectionStatus
import com.rousecontext.app.ui.screens.DashboardState
import com.rousecontext.app.ui.screens.IntegrationItem
import com.rousecontext.app.ui.screens.IntegrationStatus
import com.rousecontext.mcp.core.AuthorizationCodeManager
import com.rousecontext.mcp.core.TokenStore
import com.rousecontext.notifications.audit.AuditDao
import com.rousecontext.tunnel.CertificateStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the main dashboard screen with connection state, integration list,
 * and recent audit activity.
 */
class MainDashboardViewModel(
    private val integrations: List<McpIntegration>,
    private val stateStore: IntegrationStateStore,
    private val tokenStore: TokenStore,
    private val auditDao: AuditDao,
    private val certificateStore: CertificateStore? = null,
    private val authorizationCodeManager: AuthorizationCodeManager? = null
) : ViewModel() {

    private val connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    private val isOnboarded = MutableStateFlow(true)

    init {
        viewModelScope.launch {
            isOnboarded.value = certificateStore?.getSubdomain() != null
        }
    }

    private val recentAuditFlow = auditDao.observeRecent(
        startMillis = System.currentTimeMillis() - RECENT_WINDOW_MS,
        endMillis = Long.MAX_VALUE,
        limit = RECENT_LIMIT
    )

    val state: StateFlow<DashboardState> = combine(
        connectionStatus,
        stateStore.observeChanges().onStart { emit(Unit) },
        recentAuditFlow,
        isOnboarded
    ) { connection, _, recentEntries, onboarded ->
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
                    url = "https://<device>.rousecontext.com${integration.path}"
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

        val pendingAuthCount = authorizationCodeManager?.pendingRequests()?.size ?: 0

        DashboardState(
            connectionStatus = connection,
            integrations = items,
            recentActivity = recent,
            hasMoreIntegrationsToAdd = hasMoreToAdd,
            pendingAuthRequestCount = pendingAuthCount,
            isOnboarded = onboarded
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = DashboardState()
    )

    /**
     * Manually refreshes onboarding state. Integration and audit data are now
     * reactive and do not need manual refresh.
     */
    fun refresh() {
        viewModelScope.launch {
            isOnboarded.value = certificateStore?.getSubdomain() != null
        }
    }

    fun setConnectionStatus(status: ConnectionStatus) {
        connectionStatus.value = status
    }

    companion object {
        private const val RECENT_LIMIT = 5
        private const val RECENT_WINDOW_MS = 24 * 60 * 60 * 1000L
        private const val STOP_TIMEOUT_MS = 5_000L
        private val TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())
    }
}
