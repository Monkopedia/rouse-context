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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rousecontext.app.ui.components.SwitchRow
import com.rousecontext.app.ui.components.appBarColors
import com.rousecontext.app.ui.theme.RouseContextTheme
import com.rousecontext.app.ui.theme.SuccessGreen
import com.rousecontext.app.ui.viewmodels.OutreachSetupState

/**
 * Content-only variant used inside the persistent Scaffold in AppNavigation.
 */
@Composable
fun OutreachSetupContent(
    state: OutreachSetupState = OutreachSetupState(),
    mode: SetupMode = SetupMode.SETUP,
    onDndToggled: (Boolean) -> Unit = {},
    onGrantDnd: () -> Unit = {},
    onEnable: () -> Unit = {},
    onCancel: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    OutreachSetupBody(
        state = state,
        mode = mode,
        onDndToggled = onDndToggled,
        onGrantDnd = onGrantDnd,
        onEnable = onEnable,
        onCancel = onCancel,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutreachSetupScreen(
    state: OutreachSetupState = OutreachSetupState(),
    onDndToggled: (Boolean) -> Unit = {},
    onGrantDnd: () -> Unit = {},
    onEnable: () -> Unit = {},
    onCancel: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Outreach") },
                colors = appBarColors(),
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        OutreachSetupBody(
            state = state,
            onDndToggled = onDndToggled,
            onGrantDnd = onGrantDnd,
            onEnable = onEnable,
            onCancel = onCancel,
            modifier = Modifier.padding(padding)
        )
    }
}

@Composable
private fun OutreachSetupBody(
    state: OutreachSetupState,
    mode: SetupMode = SetupMode.SETUP,
    onDndToggled: (Boolean) -> Unit = {},
    onGrantDnd: () -> Unit = {},
    onEnable: () -> Unit = {},
    onCancel: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Allow AI clients to take actions on your device: launch apps, " +
                "open links, copy to clipboard, and send you notifications.",
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "No special permissions are needed for basic tools.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Every action is logged in the app's audit history.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // DND section
        Text(
            text = "Do Not Disturb Control",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            SwitchRow(
                title = "Allow DND changes",
                subtitle = "This is a sensitive permission. When enabled, " +
                    "AI clients can change your Do Not Disturb settings.",
                checked = state.dndToggled,
                onCheckedChange = onDndToggled,
                expandedContent = {
                    Column(
                        modifier = Modifier.padding(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = 16.dp
                        )
                    ) {
                        if (state.dndPermissionGranted) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Granted",
                                    modifier = Modifier.size(20.dp),
                                    tint = SuccessGreen
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Permission granted",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        } else {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                                )
                            ) {
                                Text(
                                    text = "DND access requires a special permission. " +
                                        "You'll be taken to system settings to grant it.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedButton(
                                onClick = onGrantDnd,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Grant DND Access")
                            }
                        }
                    }
                }
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        val buttonText = if (mode == SetupMode.SETTINGS) "Save" else "Enable"

        Button(
            onClick = onEnable,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(buttonText)
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

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun OutreachSetupPreview() {
    RouseContextTheme(darkTheme = true) {
        OutreachSetupScreen()
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun OutreachSetupDndPreview() {
    RouseContextTheme(darkTheme = true) {
        OutreachSetupScreen(
            state = OutreachSetupState(
                dndToggled = true,
                dndPermissionGranted = true
            )
        )
    }
}
