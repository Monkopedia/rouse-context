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
import com.rousecontext.mcp.core.TokenInfo
import com.rousecontext.mcp.core.TokenStore
import com.rousecontext.notifications.audit.AuditDao
import com.rousecontext.notifications.audit.AuditEntry as AuditEntryEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Manages a specific integration's detail screen: status, recent activity,
 * and authorized clients.
 *
 * State is driven by three reactive sources:
 *  - [IntegrationStateStore.observeChanges] for user-enable flips
 *  - [TokenStore.tokensFlow] for authorized-client updates (new approvals, revokes)
 *  - [AuditDao.observeByDateRange] for live tool-call audit entries
 *
 * When any source emits, [state] recomputes so the screen live-updates without
 * needing the user to leave and re-enter.
 */
@Suppress("OPT_IN_USAGE")
class IntegrationManageViewModel(
    private val integrations: List<McpIntegration>,
    private val stateStore: IntegrationStateStore,
    private val tokenStore: TokenStore,
    private val auditDao: AuditDao,
    private val urlProvider: McpUrlProvider
) : ViewModel() {

    private val integrationId = MutableStateFlow("")

    val state: StateFlow<IntegrationManageState> = integrationId
        .flatMapLatest { id ->
            if (id.isEmpty()) {
                flowOf(IntegrationManageState())
            } else {
                buildStateFlow(id)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = IntegrationManageState()
        )

    private fun buildStateFlow(id: String): Flow<IntegrationManageState> {
        val integration = integrations.find { it.id == id }
            ?: return flowOf(IntegrationManageState())

        val tokens = tokenStore.tokensFlow(id)
        val audit = auditDao.observeByDateRange(
            startMillis = 0L,
            endMillis = Long.MAX_VALUE,
            provider = id
        )
        val changes = stateStore.observeChanges().onStart { emit(Unit) }

        return combine(tokens, audit, changes) { tokenList, auditEntries, _ ->
            buildState(id, integration, tokenList, auditEntries)
        }
    }

    private suspend fun buildState(
        id: String,
        integration: McpIntegration,
        tokenList: List<TokenInfo>,
        auditEntries: List<AuditEntryEntity>
    ): IntegrationManageState {
        val derived = deriveIntegrationState(
            userEnabled = stateStore.isUserEnabled(id),
            wasEverEnabled = stateStore.wasEverEnabled(id),
            isAvailable = integration.isAvailable(),
            hasTokens = tokenList.isNotEmpty()
        )

        val recent = auditEntries.take(RECENT_LIMIT).map { entry ->
            AuditEntry(
                id = entry.id,
                time = TIME_FORMAT.format(Date(entry.timestampMillis)),
                toolName = entry.toolName,
                durationMs = entry.durationMillis
            )
        }

        val clients = tokenList.map { token ->
            AuthorizedClient(
                name = token.label,
                authorizedDate = DATE_FORMAT.format(Date(token.createdAt)),
                lastUsed = DATE_FORMAT.format(Date(token.lastUsedAt))
            )
        }

        return IntegrationManageState(
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

    fun loadIntegration(id: String) {
        integrationId.value = id
    }

    fun disable() {
        val id = integrationId.value
        if (id.isNotEmpty()) {
            viewModelScope.launch {
                stateStore.setUserEnabled(id, false)
            }
        }
    }

    fun revokeClient(clientName: String) {
        val id = integrationId.value
        if (id.isNotEmpty()) {
            val match = tokenStore.listTokens(id).find { it.label == clientName }
            if (match != null) {
                tokenStore.revokeByClientId(id, match.clientId)
            }
        }
    }

    /**
     * Kept for API compatibility with callers. Underlying flows are reactive,
     * so explicit refresh is no longer needed.
     */
    fun refresh() {
        // No-op: state recomputes automatically when tokens, audit entries,
        // or integration-state changes are emitted.
    }

    companion object {
        private const val RECENT_LIMIT = 20
        private const val STOP_TIMEOUT_MS = 5_000L
        private val TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val DATE_FORMAT = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    }
}
