package com.rousecontext.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rousecontext.app.ui.theme.AmberAccent
import com.rousecontext.app.ui.theme.RouseContextTheme
import com.rousecontext.app.ui.theme.TealPrimary

@Immutable
data class SettingsState(
    val idleTimeoutMinutes: Int = 5,
    val idleTimeoutDisabled: Boolean = false,
    val batteryOptimizationExempt: Boolean = false,
    val postSessionMode: String = "Summary",
    val canRotateAddress: Boolean = true,
    val rotationCooldownMessage: String? = null,
    val showBatteryWarning: Boolean = true,
    val versionName: String = "0.1.0"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsState = SettingsState(),
    onIdleTimeoutChanged: (Int) -> Unit = {},
    onDisableTimeoutToggled: (Boolean) -> Unit = {},
    onPostSessionModeChanged: (String) -> Unit = {},
    onGenerateNewAddress: () -> Unit = {},
    onFixBatteryOptimization: () -> Unit = {},
    onTabSelected: (Int) -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Settings") })
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
                    selected = false,
                    onClick = { onTabSelected(1) },
                    icon = { Icon(Icons.Default.History, contentDescription = "Audit") },
                    label = { Text("Audit") }
                )
                NavigationBarItem(
                    selected = true,
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
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Connection section
            SectionHeader("Connection")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingsDropdown(
                        label = "Idle timeout",
                        selected = "${state.idleTimeoutMinutes} min",
                        options = listOf("2 min", "5 min", "10 min"),
                        onSelected = { value ->
                            val minutes = value.replace(" min", "").toIntOrNull() ?: 5
                            onIdleTimeoutChanged(minutes)
                        }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Disable timeout", style = MaterialTheme.typography.bodyLarge)
                            if (!state.batteryOptimizationExempt) {
                                Text(
                                    "(requires battery exemption)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = state.idleTimeoutDisabled,
                            onCheckedChange = onDisableTimeoutToggled,
                            enabled = state.batteryOptimizationExempt
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Notifications section
            SectionHeader("Notifications")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingsDropdown(
                        label = "After session",
                        selected = state.postSessionMode,
                        options = listOf("Summary", "Each usage", "Suppress"),
                        onSelected = onPostSessionModeChanged
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Security section
            SectionHeader("Security")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Generate new address", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = state.rotationCooldownMessage ?: "(once per 30 days)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = onGenerateNewAddress,
                            enabled = state.canRotateAddress,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = TealPrimary
                            )
                        ) {
                            Text("Rotate")
                        }
                    }
                }
            }

            // Battery warning
            if (state.showBatteryWarning) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF3A2800)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = AmberAccent
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Battery optimization",
                                style = MaterialTheme.typography.bodyLarge,
                                color = AmberAccent
                            )
                            Text(
                                "Disable to ensure reliable wake-ups.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFFE0A0)
                            )
                        }
                        OutlinedButton(
                            onClick = onFixBatteryOptimization,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = AmberAccent
                            )
                        ) {
                            Text("Fix this")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // About section
            SectionHeader("About")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Version ${state.versionName}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("Apache 2.0 License", style = MaterialTheme.typography.bodyMedium)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDropdown(
    label: String,
    selected: String,
    options: List<String>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = selected,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier
                    .width(150.dp)
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
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
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun SettingsPreview() {
    RouseContextTheme(darkTheme = true) {
        SettingsScreen()
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun SettingsNoBatteryWarningPreview() {
    RouseContextTheme(darkTheme = true) {
        SettingsScreen(
            state = SettingsState(
                showBatteryWarning = false,
                batteryOptimizationExempt = true
            )
        )
    }
}
