package com.rousecontext.app.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rousecontext.app.R
import com.rousecontext.app.ui.components.ErrorState
import com.rousecontext.app.ui.components.ListDivider
import com.rousecontext.app.ui.components.ListRow
import com.rousecontext.app.ui.components.LoadingIndicator
import com.rousecontext.app.ui.components.SectionHeader
import com.rousecontext.app.ui.components.appBarColors
import com.rousecontext.app.ui.components.navBarContainerColor
import com.rousecontext.app.ui.components.navBarItemColors
import com.rousecontext.app.ui.theme.RouseContextTheme

@Immutable
data class AuditHistoryEntry(
    val id: Long = 0,
    val time: String,
    val toolName: String,
    val provider: String = "",
    val durationMs: Long,
    val arguments: String,
    val timestampMillis: Long = 0L,
    val argumentsJson: String? = null,
    val resultJson: String? = null
)

/**
 * A single row in the audit history list. Either a real tool call (clickable
 * into [AuditDetailScreen]) or a plain MCP JSON-RPC request captured when the
 * "Show all MCP messages" toggle is enabled.
 */
@Immutable
sealed class AuditHistoryItem {
    abstract val time: String
    abstract val timestampMillis: Long

    @Immutable
    data class ToolCall(val entry: AuditHistoryEntry) : AuditHistoryItem() {
        override val time: String get() = entry.time
        override val timestampMillis: Long get() = entry.timestampMillis
    }

    @Immutable
    data class Request(
        val id: Long,
        override val time: String,
        val method: String,
        val provider: String,
        val durationMs: Long,
        val resultBytes: Int?,
        override val timestampMillis: Long
    ) : AuditHistoryItem()
}

@Immutable
data class AuditHistoryGroup(val dateLabel: String, val items: List<AuditHistoryItem>) {
    /** Legacy accessor returning only the tool-call entries in this group. */
    val entries: List<AuditHistoryEntry>
        get() = items.filterIsInstance<AuditHistoryItem.ToolCall>().map { it.entry }

    companion object {
        /**
         * Build a group from a flat list of tool-call entries (the only type
         * the view ever held before the "Show all MCP messages" toggle
         * landed). Previews and screenshot tests can call this without caring
         * about the sealed hierarchy.
         */
        fun ofEntries(dateLabel: String, entries: List<AuditHistoryEntry>): AuditHistoryGroup =
            AuditHistoryGroup(
                dateLabel = dateLabel,
                items = entries.map { AuditHistoryItem.ToolCall(it) }
            )
    }
}

@Immutable
data class AuditHistoryState(
    val groups: List<AuditHistoryGroup> = emptyList(),
    val providerFilter: String = "All providers",
    val dateFilter: String = "Today",
    val availableProviders: List<String> = listOf("All providers", "health"),
    val availableDates: List<String> = listOf("Today", "Yesterday", "Last 7 days", "Last 30 days"),
    /** Whether the user has opted to see every MCP JSON-RPC message (see #105). */
    val showAllMcpMessages: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Content-only audit history composable used inside the persistent Scaffold
 * in [com.rousecontext.app.ui.navigation.AppNavigation].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditHistoryContent(
    state: AuditHistoryState = AuditHistoryState(),
    onProviderFilterChanged: (String) -> Unit = {},
    onDateFilterChanged: (String) -> Unit = {},
    onClearHistory: () -> Unit = {},
    onEntryClick: (Long) -> Unit = {},
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (state.isLoading) {
        LoadingIndicator(modifier = modifier)
        return
    }
    if (state.errorMessage != null) {
        ErrorState(
            message = state.errorMessage,
            modifier = modifier,
            onRetry = onRetry
        )
        return
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = dimensionResource(R.dimen.spacing_lg))
    ) {
        // Filters
        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_sm)))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.spacing_sm))
        ) {
            FilterDropdown(
                label = stringResource(R.string.screen_audit_history_filter_provider),
                selected = state.providerFilter,
                options = state.availableProviders,
                onSelected = onProviderFilterChanged,
                modifier = Modifier.weight(1f)
            )
            FilterDropdown(
                label = stringResource(R.string.screen_audit_history_filter_date),
                selected = state.dateFilter,
                options = state.availableDates,
                onSelected = onDateFilterChanged,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_lg)))

        if (state.groups.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.History,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_lg)))
                Text(
                    text = stringResource(R.string.screen_audit_history_empty_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_sm)))
                Text(
                    text = stringResource(R.string.screen_audit_history_empty_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                state.groups.forEach { group ->
                    item {
                        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_md)))
                        SectionHeader(group.dateLabel)
                    }
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column {
                                group.items.forEachIndexed { index, item ->
                                    when (item) {
                                        is AuditHistoryItem.ToolCall -> ToolCallRow(
                                            entry = item.entry,
                                            onClick = { onEntryClick(item.entry.id) }
                                        )
                                        is AuditHistoryItem.Request -> RequestRow(item)
                                    }
                                    if (index < group.items.lastIndex) {
                                        ListDivider()
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Text(
                text = stringResource(R.string.screen_audit_history_retention_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = dimensionResource(R.dimen.spacing_md))
            )

            OutlinedButton(
                onClick = onClearHistory,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = dimensionResource(R.dimen.spacing_lg)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                )
            ) {
                Text(stringResource(R.string.screen_audit_history_clear))
            }
        }
    }
}

/**
 * Full-screen audit history with its own Scaffold, used by previews.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditHistoryScreen(
    state: AuditHistoryState = AuditHistoryState(),
    onProviderFilterChanged: (String) -> Unit = {},
    onDateFilterChanged: (String) -> Unit = {},
    onClearHistory: () -> Unit = {},
    onEntryClick: (Long) -> Unit = {},
    onRetry: () -> Unit = {},
    onTabSelected: (Int) -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_audit_history_title)) },
                colors = appBarColors()
            )
        },
        bottomBar = {
            NavigationBar(containerColor = navBarContainerColor()) {
                val itemColors = navBarItemColors()
                NavigationBarItem(
                    selected = false,
                    onClick = { onTabSelected(0) },
                    icon = {
                        Icon(
                            Icons.Default.Home,
                            contentDescription = stringResource(
                                R.string.screen_audit_history_nav_home
                            )
                        )
                    },
                    label = { Text(stringResource(R.string.screen_audit_history_nav_home)) },
                    colors = itemColors
                )
                NavigationBarItem(
                    selected = true,
                    onClick = { onTabSelected(1) },
                    icon = {
                        Icon(
                            Icons.Default.History,
                            contentDescription = stringResource(
                                R.string.screen_audit_history_nav_audit
                            )
                        )
                    },
                    label = { Text(stringResource(R.string.screen_audit_history_nav_audit)) },
                    colors = itemColors
                )
                NavigationBarItem(
                    selected = false,
                    onClick = { onTabSelected(2) },
                    icon = {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(
                                R.string.screen_audit_history_nav_settings
                            )
                        )
                    },
                    label = { Text(stringResource(R.string.screen_audit_history_nav_settings)) },
                    colors = itemColors
                )
            }
        }
    ) { padding ->
        AuditHistoryContent(
            state = state,
            onProviderFilterChanged = onProviderFilterChanged,
            onDateFilterChanged = onDateFilterChanged,
            onClearHistory = onClearHistory,
            onEntryClick = onEntryClick,
            onRetry = onRetry,
            modifier = Modifier.padding(padding)
        )
    }
}

@Composable
private fun ToolCallRow(entry: AuditHistoryEntry, onClick: () -> Unit) {
    ListRow(onClick = onClick) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    entry.toolName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    entry.time,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row {
                Text(
                    stringResource(
                        R.string.screen_audit_history_duration_ms,
                        entry.durationMs
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(dimensionResource(R.dimen.spacing_md)))
                Text(
                    entry.arguments,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RequestRow(item: AuditHistoryItem.Request) {
    val mutedContent = MaterialTheme.colorScheme.onSurfaceVariant
    ListRow {
        Icon(
            Icons.Default.Api,
            contentDescription = null,
            tint = mutedContent,
            modifier = Modifier.size(dimensionResource(R.dimen.icon_size_sm))
        )
        Spacer(modifier = Modifier.width(dimensionResource(R.dimen.spacing_md)))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    item.method,
                    style = MaterialTheme.typography.bodyMedium,
                    color = mutedContent,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    item.time,
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedContent
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row {
                Text(
                    stringResource(
                        R.string.screen_audit_history_duration_ms,
                        item.durationMs
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = mutedContent
                )
                if (item.resultBytes != null) {
                    Spacer(modifier = Modifier.width(dimensionResource(R.dimen.spacing_md)))
                    Text(
                        stringResource(
                            R.string.screen_audit_history_result_bytes,
                            item.resultBytes
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = mutedContent
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterDropdown(
    label: String,
    selected: String,
    options: List<String>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            singleLine = true,
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

// --- Previews ---

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun AuditHistoryPopulatedPreview() {
    RouseContextTheme(darkTheme = true) {
        AuditHistoryScreen(
            state = AuditHistoryState(
                groups = listOf(
                    AuditHistoryGroup.ofEntries(
                        dateLabel = "Today",
                        entries = listOf(
                            AuditHistoryEntry(
                                time = "10:32 AM",
                                toolName = "health/get_steps",
                                durationMs = 142,
                                arguments = "{days: 7}"
                            ),
                            AuditHistoryEntry(
                                time = "10:31 AM",
                                toolName = "health/get_sleep",
                                durationMs = 89,
                                arguments = "{days: 1}"
                            ),
                            AuditHistoryEntry(
                                time = "10:31 AM",
                                toolName = "health/get_heart_rate",
                                durationMs = 201,
                                arguments = "{days: 7}"
                            )
                        )
                    ),
                    AuditHistoryGroup.ofEntries(
                        dateLabel = "Yesterday",
                        entries = listOf(
                            AuditHistoryEntry(
                                time = "3:15 PM",
                                toolName = "health/get_steps",
                                durationMs = 156,
                                arguments = "{days: 30}"
                            )
                        )
                    )
                )
            )
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun AuditHistoryEmptyPreview() {
    RouseContextTheme(darkTheme = true) {
        AuditHistoryScreen()
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun AuditHistoryFilteredPreview() {
    RouseContextTheme(darkTheme = true) {
        AuditHistoryScreen(
            state = AuditHistoryState(
                providerFilter = "health",
                dateFilter = "Last 7 days",
                groups = listOf(
                    AuditHistoryGroup.ofEntries(
                        dateLabel = "Today",
                        entries = listOf(
                            AuditHistoryEntry(
                                time = "10:32 AM",
                                toolName = "health/get_steps",
                                durationMs = 142,
                                arguments = "{days: 7}"
                            )
                        )
                    )
                )
            )
        )
    }
}

@Preview(
    showBackground = true,
    showSystemUi = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO
)
@Composable
fun AuditHistoryPopulatedLightPreview() {
    RouseContextTheme(darkTheme = false) {
        AuditHistoryScreen(
            state = AuditHistoryState(
                groups = listOf(
                    AuditHistoryGroup.ofEntries(
                        dateLabel = "Today",
                        entries = listOf(
                            AuditHistoryEntry(
                                time = "10:32 AM",
                                toolName = "health/get_steps",
                                durationMs = 142,
                                arguments = "{days: 7}"
                            ),
                            AuditHistoryEntry(
                                time = "10:31 AM",
                                toolName = "health/get_sleep",
                                durationMs = 89,
                                arguments = "{days: 1}"
                            )
                        )
                    )
                )
            )
        )
    }
}
