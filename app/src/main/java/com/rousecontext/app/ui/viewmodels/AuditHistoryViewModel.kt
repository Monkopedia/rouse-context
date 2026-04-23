package com.rousecontext.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rousecontext.api.NotificationSettingsProvider
import com.rousecontext.app.ui.screens.AuditHistoryEntry
import com.rousecontext.app.ui.screens.AuditHistoryGroup
import com.rousecontext.app.ui.screens.AuditHistoryItem
import com.rousecontext.app.ui.screens.AuditHistoryState
import com.rousecontext.app.ui.screens.DateFilterOption
import com.rousecontext.app.ui.screens.ProviderFilterOption
import com.rousecontext.notifications.FieldEncryptor
import com.rousecontext.notifications.audit.AuditDao
import com.rousecontext.notifications.audit.AuditEntry
import com.rousecontext.notifications.audit.McpRequestDao
import com.rousecontext.notifications.audit.McpRequestEntry
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Queries audit entries with provider/date filters and groups results by date.
 *
 * When the user enables "Show all MCP messages" in Settings (see issue #105),
 * the view merges generic MCP request entries alongside tool-call entries, in
 * timestamp-desc order within each day group.
 */
class AuditHistoryViewModel(
    private val auditDao: AuditDao,
    private val fieldEncryptor: FieldEncryptor? = null,
    private val mcpRequestDao: McpRequestDao? = null,
    settingsProvider: NotificationSettingsProvider? = null
) : ViewModel() {

    private val providerFilter =
        MutableStateFlow<ProviderFilterOption>(ProviderFilterOption.All)
    private val dateFilter = MutableStateFlow(DEFAULT_DATE_FILTER)
    private val refreshTrigger = MutableStateFlow(0)

    /**
     * One-shot scroll target driven by notification deep-links (#347). The
     * per-tool-call notification tap populates this so the audit screen
     * scrolls to the specific call. Nullable so consumers know when there is
     * nothing to scroll to; [consumeScrollTarget] clears it after the scroll
     * fires so a config change doesn't re-fire it.
     */
    private val _scrollTarget = MutableStateFlow<Long?>(null)
    val scrollTarget: StateFlow<Long?> get() = _scrollTarget

    private val showAllFlow: Flow<Boolean> = settingsProvider
        ?.observeSettings()
        ?.map { it.showAllMcpMessages }
        ?: flowOf(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<AuditHistoryState> = combine(
        providerFilter,
        dateFilter,
        refreshTrigger,
        showAllFlow
    ) { provider, date, _, showAll ->
        QueryInputs(provider, date, showAll)
    }.flatMapLatest { inputs ->
        val (startMillis, endMillis) = dateRangeFor(inputs.dateFilter)
        val providerArg = inputs.providerFilter.providerIdOrNull
        val toolCalls = auditDao.observeByDateRange(startMillis, endMillis, providerArg)
        val requests = if (inputs.showAll && mcpRequestDao != null) {
            mcpRequestDao.observeByDateRange(startMillis, endMillis, providerArg)
        } else {
            flowOf(emptyList())
        }
        combine(toolCalls, requests) { entries, requestEntries ->
            val groups = groupMixedByDate(entries, requestEntries, fieldEncryptor)
            AuditHistoryState(
                groups = groups,
                providerFilter = inputs.providerFilter,
                dateFilter = inputs.dateFilter,
                showAllMcpMessages = inputs.showAll,
                isLoading = false,
                errorMessage = null
            )
        }
    }.catch { cause ->
        emit(
            AuditHistoryState(
                isLoading = false,
                errorMessage = cause.message ?: "Could not load audit history."
            )
        )
    }.stateIn(
        scope = viewModelScope,
        // Fix #368: STOP_TIMEOUT_MS = 0 — tear down the upstream the moment
        // the last subscriber leaves. A non-zero grace used to serve the
        // stale cached `groups = []` snapshot back to a re-subscribing
        // consumer (notification tap) even if rows had been inserted during
        // the grace window, because Room's invalidation tracker had not yet
        // delivered the new emission through the still-alive upstream. With
        // a zero grace every re-subscribe runs a fresh query and the newly
        // inserted row is returned immediately.
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = AuditHistoryState(isLoading = true, dateFilter = DEFAULT_DATE_FILTER)
    )

    private data class QueryInputs(
        val providerFilter: ProviderFilterOption,
        val dateFilter: DateFilterOption,
        val showAll: Boolean
    )

    /**
     * Trigger re-collection. Underlying Room flow is reactive; this just bumps
     * the refresh trigger so the flatMapLatest restarts.
     */
    fun retry() {
        refreshTrigger.value++
    }

    fun setProviderFilter(provider: ProviderFilterOption) {
        providerFilter.value = provider
    }

    fun setDateFilter(date: DateFilterOption) {
        dateFilter.value = date
    }

    /**
     * Request the list to scroll to [callId] on next composition. The screen
     * collects [scrollTarget] and calls [consumeScrollTarget] once the scroll
     * completes so a config change doesn't re-scroll.
     */
    fun requestScrollTo(callId: Long) {
        _scrollTarget.value = callId
    }

    /** Clear the pending scroll target after the list has scrolled. */
    fun consumeScrollTarget() {
        _scrollTarget.value = null
    }

    fun clearHistory() {
        viewModelScope.launch {
            auditDao.deleteOlderThan(System.currentTimeMillis() + 1)
            refreshTrigger.value++
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 0L

        /**
         * Default audit-history date filter. Chosen as [DateFilterOption.LAST_7_DAYS]
         * (issue #370) so it always encloses the rolling 24 h window the
         * dashboard "Recent Activity" teaser uses — clicking "View all" from
         * a dashboard row is guaranteed to land on a filter that includes
         * the row the user just saw.
         */
        val DEFAULT_DATE_FILTER: DateFilterOption = DateFilterOption.LAST_7_DAYS

        private val TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())

        /**
         * Map a persisted [AuditEntry] Room row to the view-layer
         * [AuditHistoryEntry] used by the shared `ToolCallRow` widget. Shared
         * across the three surfaces that render tool-call rows (dashboard
         * "Recent Activity", integration manage "Recent Activity", and the
         * audit screen itself — see issue #370).
         */
        internal fun toHistoryEntry(
            entry: AuditEntry,
            fieldEncryptor: FieldEncryptor? = null
        ): AuditHistoryEntry {
            // #383: NEVER fall back to the raw argumentsJson/resultJson columns.
            // Those columns contain ciphertext at rest. If we have no encryptor
            // (surface wired without one) or decrypt returns null (malformed
            // row, key rotation, etc.) the safe choice is to render nothing;
            // anything else leaks ciphertext into the UI or across the MCP wire.
            val decryptedArgs = fieldEncryptor?.decrypt(entry.argumentsJson)
            val decryptedResult = fieldEncryptor?.decrypt(entry.resultJson)
            return AuditHistoryEntry(
                id = entry.id,
                time = TIME_FORMAT.format(Date(entry.timestampMillis)),
                toolName = entry.toolName,
                provider = entry.provider,
                durationMs = entry.durationMillis,
                arguments = decryptedArgs ?: "",
                timestampMillis = entry.timestampMillis,
                argumentsJson = decryptedArgs,
                resultJson = decryptedResult,
                clientLabel = entry.clientLabel
            )
        }

        internal fun dateRangeFor(filter: DateFilterOption): Pair<Long, Long> {
            val now = System.currentTimeMillis()
            val dayMs = 24 * 60 * 60 * 1000L
            val startOfToday = LocalDate.now()
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()

            return when (filter) {
                DateFilterOption.YESTERDAY -> Pair(startOfToday - dayMs, startOfToday)
                DateFilterOption.LAST_7_DAYS -> Pair(now - 7 * dayMs, now)
                DateFilterOption.LAST_30_DAYS -> Pair(now - 30 * dayMs, now)
                DateFilterOption.TODAY -> Pair(startOfToday, now)
            }
        }

        internal fun groupByDate(
            entries: List<AuditEntry>,
            fieldEncryptor: FieldEncryptor? = null
        ): List<AuditHistoryGroup> = groupMixedByDate(entries, emptyList(), fieldEncryptor)

        /**
         * Group a mix of tool-call audit entries and generic MCP request
         * entries by calendar day. Within each day the items are sorted by
         * timestamp descending. The display item type is a sealed hierarchy
         * so the view can render tool calls (clickable, full detail) and
         * generic requests (muted, method name) differently.
         */
        internal fun groupMixedByDate(
            toolCallEntries: List<AuditEntry>,
            requestEntries: List<McpRequestEntry>,
            fieldEncryptor: FieldEncryptor? = null
        ): List<AuditHistoryGroup> {
            val toolCallItems: List<AuditHistoryItem> = toolCallEntries.map { entry ->
                AuditHistoryItem.ToolCall(toHistoryEntry(entry, fieldEncryptor))
            }
            val requestItems: List<AuditHistoryItem> = requestEntries.map { req ->
                AuditHistoryItem.Request(
                    id = req.id,
                    time = TIME_FORMAT.format(Date(req.timestampMillis)),
                    method = req.method,
                    provider = req.provider,
                    durationMs = req.durationMillis,
                    resultBytes = req.resultBytes,
                    timestampMillis = req.timestampMillis
                )
            }
            return (toolCallItems + requestItems)
                .groupBy { item ->
                    Instant.ofEpochMilli(item.timestampMillis)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                }
                .entries
                .sortedByDescending { it.key }
                .map { (date, dayItems) ->
                    AuditHistoryGroup(
                        dateLabel = date.format(DateTimeFormatter.ofPattern("MMMM d, yyyy")),
                        items = dayItems.sortedByDescending { it.timestampMillis }
                    )
                }
        }
    }
}
