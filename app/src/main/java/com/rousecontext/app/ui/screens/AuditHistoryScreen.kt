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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rousecontext.app.ui.components.ListDivider
import com.rousecontext.app.ui.components.ListRow
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

@Immutable
data class AuditHistoryGroup(
    val dateLabel: String,
    val entries: List<AuditHistoryEntry>
)

@Immutable
data class AuditHistoryState(
    val groups: List<AuditHistoryGroup> = emptyList(),
    val providerFilter: String = "All providers",
    val dateFilter: String = "Today",
    val availableProviders: List<String> = listOf("All providers", "health"),
    val availableDates: List<String> = listOf("Today", "Yesterday", "Last 7 days", "Last 30 days")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditHistoryScreen(
    state: AuditHistoryState = AuditHistoryState(),
    onProviderFilterChanged: (String) -> Unit = {},
    onDateFilterChanged: (String) -> Unit = {},
    onClearHistory: () -> Unit = {},
    onEntryClick: (Long) -> Unit = {},
    onTabSelected: (Int) -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Audit History") }, colors = appBarColors())
        },
        bottomBar = {
            NavigationBar(containerColor = navBarContainerColor()) {
                val itemColors = navBarItemColors()
                NavigationBarItem(
                    selected = false,
                    onClick = { onTabSelected(0) },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                    label = { Text("Home") },
                    colors = itemColors
                )
                NavigationBarItem(
                    selected = true,
                    onClick = { onTabSelected(1) },
                    icon = { Icon(Icons.Default.History, contentDescription = "Audit") },
                    label = { Text("Audit") },
                    colors = itemColors
                )
                NavigationBarItem(
                    selected = false,
                    onClick = { onTabSelected(2) },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                    colors = itemColors
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // Filters
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterDropdown(
                    label = "Provider",
                    selected = state.providerFilter,
                    options = state.availableProviders,
                    onSelected = onProviderFilterChanged,
                    modifier = Modifier.weight(1f)
                )
                FilterDropdown(
                    label = "Date",
                    selected = state.dateFilter,
                    options = state.availableDates,
                    onSelected = onDateFilterChanged,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

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
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No tool calls recorded yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Activity will appear here after\nAI clients access your data.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    state.groups.forEach { group ->
                        item {
                            Spacer(modifier = Modifier.height(12.dp))
                            SectionHeader(group.dateLabel)
                        }
                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column {
                                    group.entries.forEachIndexed { index, entry ->
                                        ListRow(
                                            onClick = { onEntryClick(entry.id) }
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement =
                                                    Arrangement.SpaceBetween,
                                                    verticalAlignment =
                                                    Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        entry.toolName,
                                                        style = MaterialTheme
                                                            .typography.bodyMedium,
                                                        fontWeight = FontWeight.Medium,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    Text(
                                                        entry.time,
                                                        style = MaterialTheme
                                                            .typography.bodySmall,
                                                        color = MaterialTheme
                                                            .colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Row {
                                                    Text(
                                                        "${entry.durationMs}ms",
                                                        style = MaterialTheme
                                                            .typography.labelSmall,
                                                        color = MaterialTheme
                                                            .colorScheme.onSurfaceVariant
                                                    )
                                                    Spacer(
                                                        modifier = Modifier.width(12.dp)
                                                    )
                                                    Text(
                                                        entry.arguments,
                                                        style = MaterialTheme
                                                            .typography.labelSmall,
                                                        color = MaterialTheme
                                                            .colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                        if (index < group.entries.lastIndex) {
                                            ListDivider()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Text(
                    text = "Audit history is kept until you clear it manually.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )

                OutlinedButton(
                    onClick = onClearHistory,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                    )
                ) {
                    Text("Clear history")
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
                    AuditHistoryGroup(
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
                    AuditHistoryGroup(
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
                    AuditHistoryGroup(
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
                    AuditHistoryGroup(
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
