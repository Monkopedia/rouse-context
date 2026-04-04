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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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

@Immutable
data class AuthorizedClient(
    val name: String,
    val authorizedDate: String,
    val lastUsed: String
)

@Immutable
data class IntegrationManageState(
    val integrationName: String = "Health Connect",
    val status: IntegrationStatus = IntegrationStatus.ACTIVE,
    val url: String = "https://brave-falcon.rousecontext.com/health",
    val recentActivity: List<AuditEntry> = emptyList(),
    val authorizedClients: List<AuthorizedClient> = emptyList()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntegrationManageScreen(
    state: IntegrationManageState = IntegrationManageState(),
    onCopyUrl: () -> Unit = {},
    onViewAllActivity: () -> Unit = {},
    onRevokeClient: (String) -> Unit = {},
    onSettings: () -> Unit = {},
    onDisable: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.integrationName)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = when (state.status) {
                                IntegrationStatus.ACTIVE -> "Active"
                                IntegrationStatus.PENDING -> "Pending"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = when (state.status) {
                                IntegrationStatus.ACTIVE -> MaterialTheme.colorScheme.primary
                                IntegrationStatus.PENDING -> MaterialTheme.colorScheme.tertiary
                            }
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // URL card
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = state.url,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onCopyUrl) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy URL")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // Pending waiting message or recent activity
            if (state.status == IntegrationStatus.PENDING) {
                item {
                    Text(
                        text = "Waiting for first client...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Add the URL above to your AI client to get started.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }
            } else {
                item {
                    Text(
                        text = "Recent Activity",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                items(state.recentActivity) { entry ->
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
                if (state.recentActivity.isNotEmpty()) {
                    item {
                        TextButton(onClick = onViewAllActivity) {
                            Text("View all")
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }

            // Authorized clients
            item {
                Text(
                    text = "Authorized Clients",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (state.authorizedClients.isEmpty()) {
                item {
                    Text(
                        text = "(none yet)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }
            } else {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            state.authorizedClients.forEachIndexed { index, client ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            client.name,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        Text(
                                            "${client.authorizedDate} \u00B7 ${client.lastUsed}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    TextButton(onClick = { onRevokeClient(client.name) }) {
                                        Text("Revoke")
                                    }
                                }
                                if (index < state.authorizedClients.lastIndex) {
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }

            // Action buttons
            item {
                Button(
                    onClick = onSettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Settings")
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onDisable,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Disable Integration")
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun IntegrationManageActivePreview() {
    RouseContextTheme(darkTheme = true) {
        IntegrationManageScreen(
            state = IntegrationManageState(
                status = IntegrationStatus.ACTIVE,
                recentActivity = listOf(
                    AuditEntry("10:32 AM", "get_steps", 142),
                    AuditEntry("10:31 AM", "get_sleep", 89)
                ),
                authorizedClients = listOf(
                    AuthorizedClient("Claude Desktop", "Apr 2", "2 hours ago"),
                    AuthorizedClient("Cursor", "Apr 3", "just now")
                )
            )
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun IntegrationManagePendingPreview() {
    RouseContextTheme(darkTheme = true) {
        IntegrationManageScreen(
            state = IntegrationManageState(
                status = IntegrationStatus.PENDING
            )
        )
    }
}
