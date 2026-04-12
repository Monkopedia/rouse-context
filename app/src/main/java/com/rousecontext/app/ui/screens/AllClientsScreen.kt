package com.rousecontext.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rousecontext.app.ui.components.ListDivider
import com.rousecontext.app.ui.components.ListRow
import com.rousecontext.app.ui.components.SectionHeader
import com.rousecontext.app.ui.navigation.ConfigureNavBar
import com.rousecontext.app.ui.theme.RouseContextTheme

@Composable
fun AllClientsContent(
    integrationName: String = "",
    clients: List<AuthorizedClient> = emptyList(),
    onRevokeClient: (String) -> Unit = {},
    onBack: () -> Unit = {}
) {
    ConfigureNavBar(
        title = "Authorized Clients",
        showBackButton = true,
        showBottomBar = false,
        showTopBar = true,
        onBackPressed = onBack
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        if (integrationName.isNotEmpty()) {
            SectionHeader(integrationName)
        }

        if (clients.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.People,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No authorized clients",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    clients.forEachIndexed { index, client ->
                        ListRow {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    client.name,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    "Authorized ${client.authorizedDate}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "Last used ${client.lastUsed}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(
                                onClick = { onRevokeClient(client.name) }
                            ) {
                                Text(
                                    "Revoke",
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        if (index < clients.lastIndex) {
                            ListDivider()
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun AllClientsPreview() {
    RouseContextTheme(darkTheme = true) {
        AllClientsContent(
            integrationName = "Health Connect",
            clients = listOf(
                AuthorizedClient("Claude Desktop", "Apr 2, 2025", "2 hours ago"),
                AuthorizedClient("Cursor", "Apr 3, 2025", "just now"),
                AuthorizedClient("VS Code", "Apr 5, 2025", "yesterday"),
                AuthorizedClient("Windsurf", "Apr 7, 2025", "3 days ago"),
                AuthorizedClient("Zed", "Apr 8, 2025", "1 week ago")
            )
        )
    }
}
