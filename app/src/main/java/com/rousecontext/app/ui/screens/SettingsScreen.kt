package com.rousecontext.app.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rousecontext.app.ui.components.SectionHeader
import com.rousecontext.app.ui.components.SwitchRow
import com.rousecontext.app.ui.components.appBarColors
import com.rousecontext.app.ui.components.navBarContainerColor
import com.rousecontext.app.ui.components.navBarItemColors
import com.rousecontext.app.ui.theme.LocalExtendedColors
import com.rousecontext.app.ui.theme.RouseContextTheme
import com.rousecontext.app.ui.theme.SuccessGreen

enum class TrustOverallStatus {
    VERIFIED,
    WARNING,
    ALERT
}

@Immutable
data class TrustStatusState(
    val lastCheckTime: Long = 0L,
    val selfCheckResult: String = "",
    val ctCheckResult: String = "",
    val certFingerprint: String = "",
    val overallStatus: TrustOverallStatus = TrustOverallStatus.VERIFIED
)

@Immutable
data class SettingsState(
    val idleTimeoutMinutes: Int = 5,
    val idleTimeoutDisabled: Boolean = false,
    val batteryOptimizationExempt: Boolean = false,
    val postSessionMode: String = "Summary",
    val themeMode: String = "Auto",
    val securityCheckInterval: String = "12 hours",
    val canRotateAddress: Boolean = true,
    val rotationCooldownMessage: String? = null,
    val showBatteryWarning: Boolean = true,
    val versionName: String = "0.1.0",
    val trustStatus: TrustStatusState? = null
)

/**
 * Content-only settings composable used inside the persistent Scaffold
 * in [com.rousecontext.app.ui.navigation.AppNavigation].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    state: SettingsState = SettingsState(),
    onIdleTimeoutChanged: (Int) -> Unit = {},
    onDisableTimeoutToggled: (Boolean) -> Unit = {},
    onPostSessionModeChanged: (String) -> Unit = {},
    onThemeModeChanged: (String) -> Unit = {},
    onSecurityCheckIntervalChanged: (String) -> Unit = {},
    onGenerateNewAddress: () -> Unit = {},
    onFixBatteryOptimization: () -> Unit = {},
    onAcknowledgeAlert: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Appearance section
        SectionHeader("Appearance")
        SettingsSectionCard {
            Column(modifier = Modifier.padding(16.dp)) {
                SettingsDropdown(
                    label = "Theme",
                    selected = state.themeMode,
                    options = listOf("Light", "Dark", "Auto"),
                    onSelected = onThemeModeChanged
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Connection section
        SectionHeader("Connection")
        SettingsSectionCard {
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                SettingsDropdown(
                    label = "Idle timeout",
                    selected = "${state.idleTimeoutMinutes} min",
                    options = listOf("2 min", "5 min", "10 min"),
                    onSelected = { value ->
                        val minutes = value.replace(" min", "").toIntOrNull() ?: 5
                        onIdleTimeoutChanged(minutes)
                    }
                )
            }
            SwitchRow(
                title = "Disable timeout",
                subtitle = if (!state.batteryOptimizationExempt) {
                    "(requires battery exemption)"
                } else {
                    null
                },
                checked = state.idleTimeoutDisabled,
                onCheckedChange = onDisableTimeoutToggled,
                enabled = state.batteryOptimizationExempt
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Audit notifications section
        SectionHeader("Audit Notifications")
        SettingsSectionCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Show a notification when AI clients use your tools",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
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
        SettingsSectionCard {
            Column(modifier = Modifier.padding(16.dp)) {
                SettingsDropdown(
                    label = "Check interval",
                    selected = state.securityCheckInterval,
                    options = listOf("6 hours", "12 hours", "24 hours"),
                    onSelected = onSecurityCheckIntervalChanged
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Generate new address", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = state.rotationCooldownMessage
                                ?: "Changes take effect immediately",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = onGenerateNewAddress,
                        enabled = state.canRotateAddress,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
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
            val ext = LocalExtendedColors.current
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = ext.warningContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = ext.warningAccent
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Battery optimization",
                            style = MaterialTheme.typography.bodyLarge,
                            color = ext.warningAccent
                        )
                        Text(
                            "Disable to ensure reliable wake-ups.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ext.onWarningContainer
                        )
                    }
                    OutlinedButton(
                        onClick = onFixBatteryOptimization,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = ext.warningAccent
                        ),
                        border = BorderStroke(1.dp, ext.warningAccent)
                    ) {
                        Text("Fix this")
                    }
                }
            }
        }

        // Trust Status section
        if (state.trustStatus != null) {
            Spacer(modifier = Modifier.height(16.dp))
            SectionHeader("Trust Status")
            TrustStatusSection(
                trustStatus = state.trustStatus,
                onAcknowledgeAlert = onAcknowledgeAlert
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // About section
        SectionHeader("About")
        SettingsSectionCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Version ${state.versionName}",
                    style = MaterialTheme.typography.bodyMedium
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                Text("Apache 2.0 License", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 * Full-screen settings with its own Scaffold, used by previews.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsState = SettingsState(),
    onIdleTimeoutChanged: (Int) -> Unit = {},
    onDisableTimeoutToggled: (Boolean) -> Unit = {},
    onPostSessionModeChanged: (String) -> Unit = {},
    onThemeModeChanged: (String) -> Unit = {},
    onSecurityCheckIntervalChanged: (String) -> Unit = {},
    onGenerateNewAddress: () -> Unit = {},
    onFixBatteryOptimization: () -> Unit = {},
    onAcknowledgeAlert: () -> Unit = {},
    onTabSelected: (Int) -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
                        Icon(Icons.Default.Home, contentDescription = "Home")
                    },
                    label = { Text("Home") },
                    colors = itemColors
                )
                NavigationBarItem(
                    selected = false,
                    onClick = { onTabSelected(1) },
                    icon = {
                        Icon(
                            Icons.Default.History,
                            contentDescription = "Audit"
                        )
                    },
                    label = { Text("Audit") },
                    colors = itemColors
                )
                NavigationBarItem(
                    selected = true,
                    onClick = { onTabSelected(2) },
                    icon = {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings"
                        )
                    },
                    label = { Text("Settings") },
                    colors = itemColors
                )
            }
        }
    ) { padding ->
        SettingsContent(
            state = state,
            onIdleTimeoutChanged = onIdleTimeoutChanged,
            onDisableTimeoutToggled = onDisableTimeoutToggled,
            onPostSessionModeChanged = onPostSessionModeChanged,
            onThemeModeChanged = onThemeModeChanged,
            onSecurityCheckIntervalChanged = onSecurityCheckIntervalChanged,
            onGenerateNewAddress = onGenerateNewAddress,
            onFixBatteryOptimization = onFixBatteryOptimization,
            onAcknowledgeAlert = onAcknowledgeAlert,
            modifier = Modifier.padding(padding)
        )
    }
}

private const val FINGERPRINT_TRUNCATE_LENGTH = 23

@Composable
private fun TrustStatusSection(trustStatus: TrustStatusState, onAcknowledgeAlert: () -> Unit = {}) {
    val ext = LocalExtendedColors.current
    val (statusIcon, statusColor, statusLabel) = when (trustStatus.overallStatus) {
        TrustOverallStatus.VERIFIED -> Triple(Icons.Default.CheckCircle, SuccessGreen, "Verified")
        TrustOverallStatus.WARNING -> Triple(Icons.Default.Warning, ext.warningAccent, "Warning")
        TrustOverallStatus.ALERT -> Triple(Icons.Default.Error, ext.alertContent, "Alert")
    }

    val timeAgo = formatTimeAgo(trustStatus.lastCheckTime)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (trustStatus.overallStatus == TrustOverallStatus.ALERT) {
            CardDefaults.cardColors(containerColor = ext.alertContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Overall status row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    statusIcon,
                    contentDescription = statusLabel,
                    tint = statusColor,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = statusColor
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(12.dp))

            // Self-check row
            TrustCheckRow(
                label = "Self-check",
                result = trustStatus.selfCheckResult,
                timeAgo = timeAgo
            )

            Spacer(modifier = Modifier.height(8.dp))

            // CT log row
            TrustCheckRow(
                label = "CT log",
                result = trustStatus.ctCheckResult,
                timeAgo = timeAgo
            )

            // Cert fingerprint
            if (trustStatus.certFingerprint.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(12.dp))
                CertFingerprintRow(trustStatus.certFingerprint)
            }

            // Acknowledge button when alert is active
            if (trustStatus.overallStatus == TrustOverallStatus.ALERT) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Integration requests are blocked while an alert is active.",
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onAcknowledgeAlert,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = statusColor
                    ),
                    border = BorderStroke(1.dp, statusColor)
                ) {
                    Text("Acknowledge")
                }
            }
        }
    }
}

@Composable
private fun TrustCheckRow(label: String, result: String, timeAgo: String) {
    val ext = LocalExtendedColors.current
    val (icon, color, displayResult) = when (result) {
        "verified" -> Triple(Icons.Default.CheckCircle, SuccessGreen, "Verified")
        "warning" -> Triple(Icons.Default.Warning, ext.warningAccent, "Unable to verify")
        "alert" -> Triple(Icons.Default.Error, ext.alertContent, "Verification failed")
        else -> Triple(
            Icons.Default.Warning,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "Not checked"
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$label: $displayResult",
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            modifier = Modifier.weight(1f)
        )
        if (timeAgo.isNotEmpty()) {
            Text(
                text = timeAgo,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CertFingerprintRow(fingerprint: String) {
    var expanded by remember { mutableStateOf(false) }
    val displayFingerprint = if (expanded || fingerprint.length <= FINGERPRINT_TRUNCATE_LENGTH) {
        fingerprint
    } else {
        fingerprint.take(FINGERPRINT_TRUNCATE_LENGTH) + "..."
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Text(
            text = "Certificate fingerprint",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = displayFingerprint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun formatTimeAgo(epochMillis: Long): String {
    if (epochMillis == 0L) return ""
    val now = System.currentTimeMillis()
    val diffMinutes = (now - epochMillis) / 60_000
    return when {
        diffMinutes < 1 -> "just now"
        diffMinutes < 60 -> "$diffMinutes min ago"
        diffMinutes < 1440 -> "${diffMinutes / 60} hours ago"
        else -> "${diffMinutes / 1440} days ago"
    }
}

@Composable
private fun SettingsSectionCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        content()
    }
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

@Preview(
    showBackground = true,
    showSystemUi = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO
)
@Composable
fun SettingsLightPreview() {
    RouseContextTheme(darkTheme = false) {
        SettingsScreen()
    }
}

@Preview(
    showBackground = true,
    showSystemUi = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO
)
@Composable
fun SettingsTrustStatusLightPreview() {
    RouseContextTheme(darkTheme = false) {
        SettingsScreen(
            state = SettingsState(
                trustStatus = TrustStatusState(
                    lastCheckTime = System.currentTimeMillis() - 300_000,
                    selfCheckResult = "verified",
                    ctCheckResult = "warning",
                    certFingerprint = "SHA256:AB:CD:EF:12:34:56:78:90",
                    overallStatus = TrustOverallStatus.WARNING
                )
            )
        )
    }
}
