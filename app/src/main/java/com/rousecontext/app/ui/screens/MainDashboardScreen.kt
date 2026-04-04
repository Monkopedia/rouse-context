package com.rousecontext.app.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rousecontext.app.ui.theme.RouseContextTheme

enum class ConnectionStatus { CONNECTED, DISCONNECTED }

enum class IntegrationStatus { ACTIVE, PENDING }

@Immutable
data class IntegrationItem(
    val id: String,
    val name: String,
    val status: IntegrationStatus,
    val url: String
)

@Immutable
data class AuditEntry(
    val time: String,
    val toolName: String,
    val durationMs: Long
)

sealed interface CertBanner {
    data object Renewing : CertBanner
    data class Expired(val renewalInProgress: Boolean) : CertBanner
    data class RateLimited(val retryDate: String) : CertBanner
    data class Onboarding(
        val generatingKeysDone: Boolean,
        val registeringDone: Boolean,
        val issuingCert: Boolean
    ) : CertBanner
}

@Immutable
data class DashboardState(
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val activeSessionCount: Int = 0,
    val integrations: List<IntegrationItem> = emptyList(),
    val recentActivity: List<AuditEntry> = emptyList(),
    val certBanner: CertBanner? = null,
    val hasMoreIntegrationsToAdd: Boolean = true
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainDashboardScreen(
    state: DashboardState = DashboardState(),
    selectedTab: Int = 0,
    onAddIntegration: () -> Unit = {},
    onIntegrationClick: (String) -> Unit = {},
    onCopyUrl: (String) -> Unit = {},
    onViewAllActivity: () -> Unit = {},
    onRetryRenewal: () -> Unit = {},
    onTabSelected: (Int) -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rouse Context") }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { onTabSelected(0) },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                    label = { Text("Home") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { onTabSelected(1) },
                    icon = { Icon(Icons.Default.History, contentDescription = "Audit") },
                    label = { Text("Audit") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { onTabSelected(2) },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") }
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // Connection status
            item {
                ConnectionStatusRow(state.connectionStatus, state.activeSessionCount)
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Cert banner
            state.certBanner?.let { banner ->
                item {
                    CertBannerCard(banner, onRetryRenewal)
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // Integrations header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Integrations",
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (state.integrations.isNotEmpty() && state.hasMoreIntegrationsToAdd) {
                        IconButton(onClick = onAddIntegration) {
                            Icon(Icons.Default.Add, contentDescription = "Add integration")
                        }
                    }
                }
            }

            // Empty state or integration list
            if (state.integrations.isEmpty()) {
                item {
                    EmptyIntegrationsCard(onAddIntegration)
                }
            } else {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            state.integrations.forEachIndexed { index, integration ->
                                IntegrationRow(
                                    integration = integration,
                                    onClick = { onIntegrationClick(integration.id) },
                                    onCopy = { onCopyUrl(integration.url) }
                                )
                                if (index < state.integrations.lastIndex) {
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }

            // Recent activity
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Recent Activity",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (state.recentActivity.isEmpty()) {
                item {
                    Text(
                        text = "(empty)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(state.recentActivity) { entry ->
                    ActivityRow(entry)
                }
                item {
                    TextButton(onClick = onViewAllActivity) {
                        Text("View all")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatusRow(status: ConnectionStatus, sessionCount: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.Circle,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = when (status) {
                ConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.primary
                ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.outline
            }
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = when (status) {
                ConnectionStatus.CONNECTED ->
                    "Connected ($sessionCount active session${
                        if (sessionCount != 1) "s" else ""
                    })"
                ConnectionStatus.DISCONNECTED -> "Disconnected"
            },
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun CertBannerCard(banner: CertBanner, onRetry: () -> Unit) {
    val containerColor = when (banner) {
        is CertBanner.Expired -> if (!banner.renewalInProgress) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        }
        is CertBanner.RateLimited -> MaterialTheme.colorScheme.tertiaryContainer
        is CertBanner.Renewing -> MaterialTheme.colorScheme.secondaryContainer
        is CertBanner.Onboarding -> MaterialTheme.colorScheme.secondaryContainer
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            when (banner) {
                is CertBanner.Renewing -> {
                    Text(
                        "Refreshing your certificate...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Integrations may be briefly unavailable.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                is CertBanner.Expired -> if (banner.renewalInProgress) {
                    Text(
                        "Certificate expired. Renewing...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Integrations are offline until this completes.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        "Certificate expired. Renewal failed — check your connection.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = onRetry) { Text("Retry") }
                }
                is CertBanner.RateLimited -> {
                    Text(
                        "Certificate issuance delayed.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Will retry automatically on ${banner.retryDate}.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                is CertBanner.Onboarding -> {
                    Text("Setting up your device...", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    val checkMark = "\u2713"
                    val dots = "..."
                    val genStatus = if (banner.generatingKeysDone) checkMark else dots
                    Text(
                        text = "Generating keys $genStatus",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "Registering ${if (banner.registeringDone) checkMark else dots}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "Issuing certificate${if (banner.issuingCert) dots else ""}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyIntegrationsCard(onAdd: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "No integrations enabled yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add your first")
            }
        }
    }
}

@Composable
private fun IntegrationRow(integration: IntegrationItem, onClick: () -> Unit, onCopy: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(
                imageVector = Icons.Default.Circle,
                contentDescription = null,
                modifier = Modifier.size(10.dp),
                tint = when (integration.status) {
                    IntegrationStatus.ACTIVE -> MaterialTheme.colorScheme.primary
                    IntegrationStatus.PENDING -> MaterialTheme.colorScheme.tertiary
                }
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(integration.name, style = MaterialTheme.typography.bodyLarge)
                if (integration.status == IntegrationStatus.PENDING) {
                    Text(
                        "Waiting for client",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when (integration.status) {
                    IntegrationStatus.ACTIVE -> "Active"
                    IntegrationStatus.PENDING -> "Pending"
                },
                style = MaterialTheme.typography.labelMedium,
                color = when (integration.status) {
                    IntegrationStatus.ACTIVE -> MaterialTheme.colorScheme.primary
                    IntegrationStatus.PENDING -> MaterialTheme.colorScheme.tertiary
                }
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = onCopy) {
                Text("Copy")
            }
        }
    }
}

@Composable
private fun ActivityRow(entry: AuditEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(entry.time, style = MaterialTheme.typography.bodySmall)
        Text(entry.toolName, style = MaterialTheme.typography.bodySmall)
        Text("${entry.durationMs}ms", style = MaterialTheme.typography.bodySmall)
    }
}

// --- Previews ---

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun DashboardEmptyPreview() {
    RouseContextTheme(darkTheme = true) {
        MainDashboardScreen(state = DashboardState())
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun DashboardWithIntegrationsPreview() {
    RouseContextTheme(darkTheme = true) {
        MainDashboardScreen(
            state = DashboardState(
                connectionStatus = ConnectionStatus.CONNECTED,
                activeSessionCount = 1,
                integrations = listOf(
                    IntegrationItem(
                        id = "health",
                        name = "Health Connect",
                        status = IntegrationStatus.ACTIVE,
                        url = "https://brave-falcon.rousecontext.com/health"
                    ),
                    IntegrationItem(
                        id = "notifications",
                        name = "Notifications",
                        status = IntegrationStatus.PENDING,
                        url = "https://brave-falcon.rousecontext.com/notifications"
                    )
                ),
                recentActivity = listOf(
                    AuditEntry("10:32 AM", "health/get_steps", 142),
                    AuditEntry("10:31 AM", "health/get_sleep", 89)
                )
            )
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun DashboardCertExpiredPreview() {
    RouseContextTheme(darkTheme = true) {
        MainDashboardScreen(
            state = DashboardState(
                certBanner = CertBanner.Expired(renewalInProgress = false),
                integrations = listOf(
                    IntegrationItem(
                        id = "health",
                        name = "Health Connect",
                        status = IntegrationStatus.ACTIVE,
                        url = "https://brave-falcon.rousecontext.com/health"
                    )
                )
            )
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun DashboardCertRenewingPreview() {
    RouseContextTheme(darkTheme = true) {
        MainDashboardScreen(
            state = DashboardState(
                certBanner = CertBanner.Renewing,
                integrations = listOf(
                    IntegrationItem(
                        id = "health",
                        name = "Health Connect",
                        status = IntegrationStatus.ACTIVE,
                        url = "https://brave-falcon.rousecontext.com/health"
                    )
                )
            )
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun DashboardCertRateLimitedPreview() {
    RouseContextTheme(darkTheme = true) {
        MainDashboardScreen(
            state = DashboardState(
                certBanner = CertBanner.RateLimited(retryDate = "Apr 11")
            )
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun DashboardCertOnboardingPreview() {
    RouseContextTheme(darkTheme = true) {
        MainDashboardScreen(
            state = DashboardState(
                certBanner = CertBanner.Onboarding(
                    generatingKeysDone = true,
                    registeringDone = true,
                    issuingCert = true
                )
            )
        )
    }
}
