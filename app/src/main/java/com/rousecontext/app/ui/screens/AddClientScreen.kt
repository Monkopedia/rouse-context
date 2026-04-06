package com.rousecontext.app.ui.screens

import android.content.res.Configuration
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rousecontext.app.ui.theme.RouseContextTheme

@Immutable
data class AddClientState(
    val endpoints: List<EndpointItem> = emptyList()
)

@Immutable
data class EndpointItem(
    val integrationName: String,
    val url: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddClientScreen(
    state: AddClientState = AddClientState(),
    onCopyUrl: (String) -> Unit = {},
    onBack: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connect an AI Client") },
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
            // Endpoint URLs
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }

            itemsIndexed(state.endpoints) { index, endpoint ->
                EndpointCard(
                    endpoint = endpoint,
                    onCopy = { onCopyUrl(endpoint.url) }
                )
                if (index < state.endpoints.lastIndex) {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            // Instructions
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "How to connect",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                InstructionStep(
                    number = "1",
                    text = "Add this URL as a remote MCP server in your AI client " +
                        "(e.g. Claude, ChatGPT)"
                )
                Spacer(modifier = Modifier.height(12.dp))
                InstructionStep(
                    number = "2",
                    text = "When prompted, approve the connection on this device"
                )
                Spacer(modifier = Modifier.height(12.dp))
                InstructionStep(
                    number = "3",
                    text = "The client will appear in your authorized clients list"
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun EndpointCard(endpoint: EndpointItem, onCopy: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = endpoint.integrationName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = endpoint.url,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onCopy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Copy URL")
            }
        }
    }
}

@Composable
private fun InstructionStep(number: String, text: String) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "$number.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun AddClientScreenPreview() {
    RouseContextTheme(darkTheme = true) {
        AddClientScreen(
            state = AddClientState(
                endpoints = listOf(
                    EndpointItem(
                        integrationName = "Health Connect",
                        url = "https://brave-falcon.rousecontext.com/health/mcp"
                    )
                )
            )
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun AddClientScreenMultiplePreview() {
    RouseContextTheme(darkTheme = true) {
        AddClientScreen(
            state = AddClientState(
                endpoints = listOf(
                    EndpointItem(
                        integrationName = "Health Connect",
                        url = "https://brave-falcon.rousecontext.com/health/mcp"
                    ),
                    EndpointItem(
                        integrationName = "Notifications",
                        url = "https://brave-falcon.rousecontext.com/notifications/mcp"
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
fun AddClientScreenLightPreview() {
    RouseContextTheme(darkTheme = false) {
        AddClientScreen(
            state = AddClientState(
                endpoints = listOf(
                    EndpointItem(
                        integrationName = "Health Connect",
                        url = "https://brave-falcon.rousecontext.com/health/mcp"
                    )
                )
            )
        )
    }
}
