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
import com.rousecontext.app.ui.components.PrivacyWarningCard
import com.rousecontext.app.ui.components.appBarColors
import com.rousecontext.app.ui.theme.RouseContextTheme
import com.rousecontext.app.ui.theme.SuccessGreen
import com.rousecontext.app.ui.viewmodels.UsageSetupState

/**
 * Content-only variant used inside the persistent Scaffold in AppNavigation.
 */
@Composable
fun UsageSetupContent(
    state: UsageSetupState = UsageSetupState(),
    mode: SetupMode = SetupMode.SETUP,
    onGrantAccess: () -> Unit = {},
    onEnable: () -> Unit = {},
    onCancel: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    UsageSetupBody(
        state = state,
        mode = mode,
        onGrantAccess = onGrantAccess,
        onEnable = onEnable,
        onCancel = onCancel,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageSetupScreen(
    state: UsageSetupState = UsageSetupState(),
    onGrantAccess: () -> Unit = {},
    onEnable: () -> Unit = {},
    onCancel: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Usage Stats") },
                colors = appBarColors(),
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        UsageSetupBody(
            state = state,
            onGrantAccess = onGrantAccess,
            onEnable = onEnable,
            onCancel = onCancel,
            modifier = Modifier.padding(padding)
        )
    }
}

@Composable
private fun UsageSetupBody(
    state: UsageSetupState,
    mode: SetupMode = SetupMode.SETUP,
    onGrantAccess: () -> Unit = {},
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
            text = "Allow AI clients to see how you use your device \u2014 " +
                "which apps you use and how much time you spend in them.",
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Privacy warning
        PrivacyWarningCard(
            text = "AI clients will be able to see your complete app " +
                "usage history, including which apps you use, how " +
                "often, and when."
        )

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

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Every access is logged in the app's audit history.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.weight(1f))

        val buttonText = if (mode == SetupMode.SETTINGS) "Save" else "Enable"

        Button(
            onClick = onEnable,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.permissionGranted
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
fun UsageSetupPreview() {
    RouseContextTheme(darkTheme = true) {
        UsageSetupScreen()
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun UsageSetupGrantedPreview() {
    RouseContextTheme(darkTheme = true) {
        UsageSetupScreen(
            state = UsageSetupState(permissionGranted = true)
        )
    }
}
