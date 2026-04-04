package com.rousecontext.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
data class HealthPermission(
    val name: String,
    val granted: Boolean
)

@Immutable
data class HealthConnectSettingsState(
    val permissions: List<HealthPermission> = emptyList()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthConnectSettingsScreen(
    state: HealthConnectSettingsState = HealthConnectSettingsState(),
    onGrantPermission: (String) -> Unit = {},
    onBack: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Health Connect Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Permissions",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    state.permissions.forEachIndexed { index, permission ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = permission.granted,
                                onCheckedChange = null,
                                enabled = false
                            )
                            Text(
                                text = permission.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 8.dp)
                            )
                            if (!permission.granted) {
                                TextButton(
                                    onClick = { onGrantPermission(permission.name) }
                                ) {
                                    Text("Grant")
                                }
                            }
                        }
                        if (index < state.permissions.lastIndex) {
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun HealthConnectSettingsPreview() {
    RouseContextTheme(darkTheme = true) {
        HealthConnectSettingsScreen(
            state = HealthConnectSettingsState(
                permissions = listOf(
                    HealthPermission("Steps", granted = true),
                    HealthPermission("Heart rate", granted = true),
                    HealthPermission("Sleep", granted = true),
                    HealthPermission("Workout history", granted = false),
                    HealthPermission("HRV", granted = false)
                )
            )
        )
    }
}
