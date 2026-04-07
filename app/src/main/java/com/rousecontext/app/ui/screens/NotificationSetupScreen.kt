package com.rousecontext.app.ui.screens

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rousecontext.app.ui.theme.RouseContextTheme
import com.rousecontext.app.ui.theme.SuccessGreen
import com.rousecontext.app.ui.viewmodels.NotificationSetupState
import com.rousecontext.app.ui.viewmodels.NotificationSetupViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSetupScreen(
    state: NotificationSetupState = NotificationSetupState(),
    onGrantAccess: () -> Unit = {},
    onRetentionChanged: (Int) -> Unit = {},
    onAllowActionsChanged: (Boolean) -> Unit = {},
    onEnable: () -> Unit = {},
    onCancel: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notification Access") },
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
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Allow Rouse Context to read your notifications so AI " +
                    "clients can help you manage them.",
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Privacy warning
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Text(
                    text = "AI clients will be able to see notification titles " +
                        "and content, including messages and alerts.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(16.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Permission status
            Text(
                text = "Permission",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (state.permissionGranted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Granted",
                        modifier = Modifier.size(20.dp),
                        tint = SuccessGreen
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Access granted",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                OutlinedButton(
                    onClick = onGrantAccess,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Grant Access")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Retention picker
            Text(
                text = "Keep notification history for",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            RetentionDropdown(
                selectedDays = state.retentionDays,
                onSelected = onRetentionChanged
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Action toggle
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Allow AI to act on notifications",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "When enabled, AI clients can perform actions " +
                                "on and dismiss your notifications.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = state.allowActions,
                        onCheckedChange = onAllowActionsChanged
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = onEnable,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.permissionGranted
            ) {
                Text("Enable")
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cancel")
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RetentionDropdown(selectedDays: Int, onSelected: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = formatRetention(selectedDays),
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            NotificationSetupViewModel.RETENTION_OPTIONS.forEach { days ->
                DropdownMenuItem(
                    text = { Text(formatRetention(days)) },
                    onClick = {
                        onSelected(days)
                        expanded = false
                    }
                )
            }
        }
    }
}

private fun formatRetention(days: Int): String = when (days) {
    1 -> "1 day"
    7 -> "1 week"
    30 -> "1 month"
    90 -> "3 months"
    else -> "$days days"
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun NotificationSetupPreview() {
    RouseContextTheme(darkTheme = true) {
        NotificationSetupScreen()
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun NotificationSetupGrantedPreview() {
    RouseContextTheme(darkTheme = true) {
        NotificationSetupScreen(
            state = NotificationSetupState(
                permissionGranted = true,
                retentionDays = 30,
                allowActions = true
            )
        )
    }
}
