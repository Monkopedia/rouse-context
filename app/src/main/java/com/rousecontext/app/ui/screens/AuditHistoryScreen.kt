package com.rousecontext.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rousecontext.app.ui.theme.RouseContextTheme

@Immutable
data class AuditHistoryEntry(
    val time: String,
    val toolName: String,
    val durationMs: Long,
    val arguments: String
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
    onTabSelected: (Int) -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Audit History") })
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = false,
                    onClick = { onTabSelected(0) },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                    label = { Text("Home") }
                )
                NavigationBarItem(
                    selected = true,
                    onClick = { onTabSelected(1) },
                    icon = { Icon(Icons.Default.History, contentDescription = "Audit") },
                    label = { Text("Audit") }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = { onTabSelected(2) },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") }
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
                    Text(
                        text = "No audit entries yet.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Activity will appear here after AI clients access your data.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    state.groups.forEach { group ->
                        item {
                            Text(
                                text = group.dateLabel,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column {
                                    group.entries.forEachIndexed { index, entry ->
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    entry.time,
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                                Text(
                                                    entry.toolName,
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                            }
                                            Row {
                                                Text(
                                                    "${entry.durationMs}ms",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme
                                                        .colorScheme.onSurfaceVariant
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    "args: ${entry.arguments}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme
                                                        .colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        if (index < group.entries.lastIndex) {
                                            HorizontalDivider()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = onClearHistory,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp)
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
        TextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
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
    RouseContextTheme(dynamicColor = false) {
        AuditHistoryScreen(
            state = AuditHistoryState(
                groups = listOf(
                    AuditHistoryGroup(
                        dateLabel = "Today",
                        entries = listOf(
                            AuditHistoryEntry("10:32 AM", "health/get_steps", 142, "{days: 7}"),
                            AuditHistoryEntry("10:31 AM", "health/get_sleep", 89, "{days: 1}"),
                            AuditHistoryEntry(
                                "10:31 AM",
                                "health/get_heart_rate",
                                201,
                                "{days: 7}"
                            )
                        )
                    ),
                    AuditHistoryGroup(
                        dateLabel = "Yesterday",
                        entries = listOf(
                            AuditHistoryEntry("3:15 PM", "health/get_steps", 156, "{days: 30}")
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
    RouseContextTheme(dynamicColor = false) {
        AuditHistoryScreen()
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun AuditHistoryFilteredPreview() {
    RouseContextTheme(dynamicColor = false) {
        AuditHistoryScreen(
            state = AuditHistoryState(
                providerFilter = "health",
                dateFilter = "Last 7 days",
                groups = listOf(
                    AuditHistoryGroup(
                        dateLabel = "Today",
                        entries = listOf(
                            AuditHistoryEntry("10:32 AM", "health/get_steps", 142, "{days: 7}")
                        )
                    )
                )
            )
        )
    }
}
