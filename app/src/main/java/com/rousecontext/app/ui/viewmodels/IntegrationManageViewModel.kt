package com.rousecontext.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.api.McpIntegration
import com.rousecontext.api.deriveIntegrationState
import com.rousecontext.app.McpUrlProvider
import com.rousecontext.app.ui.screens.AuditEntry
import com.rousecontext.app.ui.screens.AuthorizedClient
import com.rousecontext.app.ui.screens.IntegrationManageState
import com.rousecontext.app.ui.screens.IntegrationStatus
import com.rousecontext.mcp.core.TokenStore
import com.rousecontext.notifications.audit.AuditDao
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Manages a specific integration's detail screen: status, recent activity,
 * and authorized clients.
 */
class IntegrationManageViewModel(
    private val integrations: List<McpIntegration>,
    private val stateStore: IntegrationStateStore,
    private val tokenStore: TokenStore,
    private val auditDao: AuditDao,
    private val urlProvider: McpUrlProvider
) : ViewModel() {

    private val integrationId = MutableStateFlow("")
    private val refreshTrigger = MutableStateFlow(0)

    val state: StateFlow<IntegrationManageState> = refreshTrigger
        .map {
            val id = integrationId.value
            val integration = integrations.find { it.id == id }
                ?: return@map IntegrationManageState()

            val derived = deriveIntegrationState(
                userEnabled = stateStore.isUserEnabled(id),
                wasEverEnabled = stateStore.wasEverEnabled(id),
                isAvailable = integration.isAvailable(),
                hasTokens = tokenStore.hasTokens(id)
            )

            val recent = auditDao.queryByDateRange(
                startMillis = 0L,
                endMillis = Long.MAX_VALUE,
                provider = id
            ).takeLast(RECENT_LIMIT).map { entry ->
                AuditEntry(
                    time = TIME_FORMAT.format(Date(entry.timestampMillis)),
                    toolName = entry.toolName,
                    durationMs = entry.durationMillis
                )
            }

            val clients = tokenStore.listTokens(id).map { token ->
                AuthorizedClient(
                    name = token.label,
                    authorizedDate = DATE_FORMAT.format(Date(token.createdAt)),
                    lastUsed = DATE_FORMAT.format(Date(token.lastUsedAt))
                )
            }

            IntegrationManageState(
                integrationName = integration.displayName,
                status = if (derived == com.rousecontext.api.IntegrationState.Active) {
                    IntegrationStatus.ACTIVE
                } else {
                    IntegrationStatus.PENDING
                },
                url = urlProvider.buildUrl(integration.id) ?: "",
                recentActivity = recent,
                authorizedClients = clients
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = IntegrationManageState()
        )

    fun loadIntegration(id: String) {
        integrationId.value = id
        refresh()
    }

    fun disable() {
        val id = integrationId.value
        if (id.isNotEmpty()) {
            stateStore.setUserEnabled(id, false)
            refresh()
        }
    }

    fun revokeClient(clientName: String) {
        val id = integrationId.value
        if (id.isNotEmpty()) {
            val match = tokenStore.listTokens(id).find { it.label == clientName }
            if (match != null) {
                tokenStore.revokeByClientId(id, match.clientId)
                refresh()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            refreshTrigger.value++
        }
    }

    companion object {
        private const val RECENT_LIMIT = 20
        private const val STOP_TIMEOUT_MS = 5_000L
        private val TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val DATE_FORMAT = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    }
}
