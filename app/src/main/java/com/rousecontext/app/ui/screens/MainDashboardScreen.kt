package com.rousecontext.app.ui.screens

import android.content.res.Configuration
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rousecontext.app.BuildConfig
import com.rousecontext.app.ui.components.ListDivider
import com.rousecontext.app.ui.components.ListRow
import com.rousecontext.app.ui.components.SectionHeader
import com.rousecontext.app.ui.components.appBarColors
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
    val hasMoreIntegrationsToAdd: Boolean = true,
    val pendingAuthRequestCount: Int = 0
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainDashboardScreen(
    state: DashboardState = DashboardState(),
    selectedTab: Int = 0,
    onAddIntegration: () -> Unit = {},
    onIntegrationClick: (String) -> Unit = {},
    onViewAllActivity: () -> Unit = {},
    onRetryRenewal: () -> Unit = {},
    onPendingAuthRequests: () -> Unit = {},
    onTabSelected: (Int) -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rouse Context") },
                colors = appBarColors()
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
            // Cert banner (most urgent - show first)
            state.certBanner?.let { banner ->
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    CertBannerCard(banner, onRetryRenewal)
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // Pending auth requests banner
            if (state.pendingAuthRequestCount > 0) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    PendingAuthBanner(
                        count = state.pendingAuthRequestCount,
                        onClick = onPendingAuthRequests
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            // Connection status (debug builds only)
            if (BuildConfig.DEBUG) {
                item {
                    if (state.certBanner == null) {
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    ConnectionStatusRow(state.connectionStatus, state.activeSessionCount)
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // Integrations header
            item {
                SectionHeader("Integrations")
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
                                    onClick = { onIntegrationClick(integration.id) }
                                )
                                if (index < state.integrations.lastIndex) {
                                    ListDivider()
                                }
                            }
                        }
                    }
                    if (state.hasMoreIntegrationsToAdd) {
                        Spacer(modifier = Modifier.height(12.dp))
                        AddIntegrationCard(onAddIntegration)
                    }
                }
            }

            // Recent activity
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SectionHeader("Recent Activity")
            }

            if (state.recentActivity.isEmpty()) {
                item {
                    EmptyRecentActivityCard()
                }
            } else {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            state.recentActivity.forEachIndexed { index, entry ->
                                ActivityRow(entry)
                                if (index < state.recentActivity.lastIndex) {
                                    ListDivider()
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onViewAllActivity) {
                            Text("View all")
                        }
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
        is CertBanner.RateLimited -> MaterialTheme.colorScheme.secondaryContainer
        is CertBanner.Renewing -> MaterialTheme.colorScheme.secondaryContainer
        is CertBanner.Onboarding -> MaterialTheme.colorScheme.secondaryContainer
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            when (banner) {
                is CertBanner.Renewing -> Icon(
                    Icons.Default.Sync,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                is CertBanner.Expired -> if (banner.renewalInProgress) {
                    Icon(
                        Icons.Default.Sync,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Icon(
                        Icons.Default.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                is CertBanner.RateLimited -> Icon(
                    Icons.Default.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                is CertBanner.Onboarding -> Icon(
                    Icons.Default.Build,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                when (banner) {
                    is CertBanner.Renewing -> {
                        Text(
                            "Refreshing your certificate...",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "Integrations may be briefly unavailable.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    is CertBanner.Expired -> if (banner.renewalInProgress) {
                        Text(
                            "Certificate expired. Renewing...",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "Integrations are offline until this completes.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text(
                            "Certificate expired",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "Renewal failed. Check your connection.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.onErrorContainer,
                                contentColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) { Text("Retry") }
                    }
                    is CertBanner.RateLimited -> {
                        Text(
                            "Certificate issuance delayed",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "Will retry automatically on ${banner.retryDate}.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    is CertBanner.Onboarding -> {
                        Text(
                            "Setting up your device...",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OnboardingStep("Generating keys", banner.generatingKeysDone)
                        Spacer(modifier = Modifier.height(4.dp))
                        OnboardingStep("Registering", banner.registeringDone)
                        Spacer(modifier = Modifier.height(4.dp))
                        OnboardingStep(
                            "Issuing certificate",
                            done = false,
                            active = banner.issuingCert
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingStep(label: String, done: Boolean, active: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            done -> Icon(
                Icons.Default.Circle,
                contentDescription = null,
                modifier = Modifier.size(8.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            active -> CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                strokeWidth = 2.dp
            )
            else -> Icon(
                Icons.Default.Circle,
                contentDescription = null,
                modifier = Modifier.size(8.dp),
                tint = MaterialTheme.colorScheme.outline
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (done) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else if (active) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun EmptyIntegrationsCard(onAdd: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "No integrations enabled yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Connect AI assistants to your phone's data.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add your first")
            }
        }
    }
}

@Composable
private fun IntegrationRow(integration: IntegrationItem, onClick: () -> Unit) {
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
    }
}

@Composable
private fun EmptyRecentActivityCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "No recent activity",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ActivityRow(entry: AuditEntry) {
    ListRow {
        Text(
            text = entry.toolName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = entry.time,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "${entry.durationMs}ms",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AddIntegrationCard(onAddIntegration: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onAddIntegration),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Add Integration",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun PendingAuthBanner(count: Int, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.GppGood,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$count pending approval${if (count != 1) "s" else ""}",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Tap to review authorization requests",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
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

@Preview(
    showBackground = true,
    showSystemUi = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO
)
@Composable
fun DashboardEmptyLightPreview() {
    RouseContextTheme(darkTheme = false) {
        MainDashboardScreen(state = DashboardState())
    }
}

@Preview(
    showBackground = true,
    showSystemUi = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO
)
@Composable
fun DashboardWithIntegrationsLightPreview() {
    RouseContextTheme(darkTheme = false) {
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
