package com.rousecontext.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rousecontext.app.ui.theme.RouseContextTheme

enum class PickerIntegrationState { AVAILABLE, DISABLED, UNAVAILABLE }

@Immutable
data class PickerIntegration(
    val id: String,
    val name: String,
    val description: String,
    val state: PickerIntegrationState
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddIntegrationPickerScreen(
    integrations: List<PickerIntegration> = emptyList(),
    onSetUp: (String) -> Unit = {},
    onReEnable: (String) -> Unit = {},
    onCancel: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Integration") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(integrations) { integration ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = if (integration.state == PickerIntegrationState.UNAVAILABLE) {
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                                    alpha = 0.5f
                                )
                            )
                        } else {
                            CardDefaults.cardColors()
                        }
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            val isUnavailable =
                                integration.state == PickerIntegrationState.UNAVAILABLE
                            Text(
                                text = integration.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (isUnavailable) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = integration.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            when (integration.state) {
                                PickerIntegrationState.AVAILABLE -> {
                                    Button(onClick = { onSetUp(integration.id) }) {
                                        Text("Set up")
                                    }
                                }
                                PickerIntegrationState.DISABLED -> {
                                    Button(onClick = { onReEnable(integration.id) }) {
                                        Text("Re-enable")
                                    }
                                }
                                PickerIntegrationState.UNAVAILABLE -> {
                                    Text(
                                        text = "Coming soon",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun AddIntegrationPickerPreview() {
    RouseContextTheme(darkTheme = true) {
        AddIntegrationPickerScreen(
            integrations = listOf(
                PickerIntegration(
                    id = "health",
                    name = "Health Connect",
                    description = "Share step count, heart rate, and sleep data with AI clients",
                    state = PickerIntegrationState.AVAILABLE
                ),
                PickerIntegration(
                    id = "notifications",
                    name = "Notifications",
                    description = "Let AI clients read your notifications",
                    state = PickerIntegrationState.UNAVAILABLE
                )
            )
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun AddIntegrationPickerWithDisabledPreview() {
    RouseContextTheme(darkTheme = true) {
        AddIntegrationPickerScreen(
            integrations = listOf(
                PickerIntegration(
                    id = "health",
                    name = "Health Connect",
                    description = "Share step count, heart rate, and sleep data with AI clients",
                    state = PickerIntegrationState.DISABLED
                ),
                PickerIntegration(
                    id = "notifications",
                    name = "Notifications",
                    description = "Let AI clients read your notifications",
                    state = PickerIntegrationState.UNAVAILABLE
                )
            )
        )
    }
}
