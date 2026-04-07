package com.rousecontext.app.ui.screens

import androidx.compose.foundation.clickable
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
import com.rousecontext.app.ui.components.appBarColors
import com.rousecontext.app.ui.theme.RouseContextTheme

enum class PickerIntegrationState { AVAILABLE, UNAVAILABLE }

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
    onCancel: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Integration") },
                colors = appBarColors(),
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
                    val isUnavailable =
                        integration.state == PickerIntegrationState.UNAVAILABLE
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .then(
                                if (!isUnavailable) {
                                    Modifier.clickable { onSetUp(integration.id) }
                                } else {
                                    Modifier
                                }
                            ),
                        colors = if (isUnavailable) {
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
                            Text(
                                text = if (isUnavailable) "Coming soon" else "Set up",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isUnavailable) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
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
fun AddIntegrationPickerWithMultiplePreview() {
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
