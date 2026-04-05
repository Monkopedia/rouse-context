package com.rousecontext.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rousecontext.app.ui.screens.AuditHistoryEntry
import com.rousecontext.app.ui.screens.AuditHistoryGroup
import com.rousecontext.app.ui.screens.AuditHistoryState
import com.rousecontext.notifications.audit.AuditDao
import com.rousecontext.notifications.audit.AuditEntry
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Queries audit entries with provider/date filters and groups results by date.
 */
class AuditHistoryViewModel(
    private val auditDao: AuditDao
) : ViewModel() {

    private val providerFilter = MutableStateFlow("All providers")
    private val dateFilter = MutableStateFlow("Today")
    private val refreshTrigger = MutableStateFlow(0)

    val state: StateFlow<AuditHistoryState> = combine(
        providerFilter,
        dateFilter,
        refreshTrigger
    ) { provider, date, _ ->
        val (startMillis, endMillis) = dateRangeFor(date)
        val providerArg = if (provider == "All providers") null else provider
        val entries = auditDao.queryByDateRange(startMillis, endMillis, providerArg)
        val groups = groupByDate(entries)

        AuditHistoryState(
            groups = groups,
            providerFilter = provider,
            dateFilter = date
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = AuditHistoryState()
    )

    fun setProviderFilter(provider: String) {
        providerFilter.value = provider
    }

    fun setDateFilter(date: String) {
        dateFilter.value = date
    }

    fun clearHistory() {
        viewModelScope.launch {
            auditDao.deleteOlderThan(System.currentTimeMillis() + 1)
            refreshTrigger.value++
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private val TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())

        internal fun dateRangeFor(filter: String): Pair<Long, Long> {
            val now = System.currentTimeMillis()
            val dayMs = 24 * 60 * 60 * 1000L
            val startOfToday = LocalDate.now()
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()

            return when (filter) {
                "Yesterday" -> Pair(startOfToday - dayMs, startOfToday)
                "Last 7 days" -> Pair(now - 7 * dayMs, now)
                "Last 30 days" -> Pair(now - 30 * dayMs, now)
                else -> Pair(startOfToday, now) // "Today"
            }
        }

        internal fun groupByDate(entries: List<AuditEntry>): List<AuditHistoryGroup> {
            return entries
                .groupBy { entry ->
                    Instant.ofEpochMilli(entry.timestampMillis)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                }
                .entries
                .sortedByDescending { it.key }
                .map { (date, dayEntries) ->
                    AuditHistoryGroup(
                        dateLabel = date.format(DateTimeFormatter.ofPattern("MMMM d, yyyy")),
                        entries = dayEntries.map { entry ->
                            AuditHistoryEntry(
                                time = TIME_FORMAT.format(Date(entry.timestampMillis)),
                                toolName = entry.toolName,
                                durationMs = entry.durationMillis,
                                arguments = ""
                            )
                        }
                    )
                }
        }
    }
}
