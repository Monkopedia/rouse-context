package com.rousecontext.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rousecontext.app.ui.components.appBarColors
import com.rousecontext.app.ui.theme.RouseContextTheme
import com.rousecontext.mcp.health.RecordTypeRegistry

/**
 * Content-only variant used inside the persistent Scaffold in AppNavigation.
 */
@Composable
fun HealthConnectSetupContent(
    mode: SetupMode = SetupMode.SETUP,
    onGrantAccess: () -> Unit = {},
    onCancel: () -> Unit = {},
    historicalAccessGranted: Boolean = false,
    onRequestHistoricalAccess: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    HealthConnectSetupBody(
        mode = mode,
        onGrantAccess = onGrantAccess,
        onCancel = onCancel,
        historicalAccessGranted = historicalAccessGranted,
        onRequestHistoricalAccess = onRequestHistoricalAccess,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthConnectSetupScreen(onGrantAccess: () -> Unit = {}, onCancel: () -> Unit = {}) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Health Connect Setup") },
                colors = appBarColors(),
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        HealthConnectSetupBody(
            onGrantAccess = onGrantAccess,
            onCancel = onCancel,
            historicalAccessGranted = false,
            onRequestHistoricalAccess = {},
            modifier = Modifier.padding(padding)
        )
    }
}

@Composable
private fun HealthConnectSetupBody(
    mode: SetupMode = SetupMode.SETUP,
    onGrantAccess: () -> Unit,
    onCancel: () -> Unit,
    historicalAccessGranted: Boolean,
    onRequestHistoricalAccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val typesByCategory = RecordTypeRegistry.allTypes
        .groupBy { it.category }
        .toSortedMap(compareBy { it.ordinal })

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Health Connect lets AI clients read your health data " +
                "to give personalized responses.",
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "We'll request access to all supported data types:",
            style = MaterialTheme.typography.titleSmall
        )

        Spacer(modifier = Modifier.height(12.dp))

        typesByCategory.forEach { (category, types) ->
            Text(
                text = category.value.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
            types.forEach { recordType ->
                Row(modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)) {
                    Text(
                        text = "\u2022",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = recordType.displayName,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = recordType.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "You can change these permissions at any time in system settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Every access is logged in the app's audit history.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(20.dp))

        HistoricalAccessSection(
            granted = historicalAccessGranted,
            onRequestHistoricalAccess = onRequestHistoricalAccess
        )

        Spacer(modifier = Modifier.weight(1f))

        val buttonText = if (mode == SetupMode.SETTINGS) {
            "Update Health Access"
        } else {
            "Grant All Health Access"
        }

        Button(
            onClick = onGrantAccess,
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

@Composable
private fun HistoricalAccessSection(granted: Boolean, onRequestHistoricalAccess: () -> Unit) {
    if (granted) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(HISTORICAL_CORNER_DP.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(
                    horizontal = HISTORICAL_H_PADDING_DP.dp,
                    vertical = HISTORICAL_V_PADDING_DP.dp
                )
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Historical access enabled",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = "AI clients can read health data recorded before " +
                        "you enabled this integration.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(HISTORICAL_CORNER_DP.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(
                    horizontal = HISTORICAL_H_PADDING_DP.dp,
                    vertical = HISTORICAL_V_PADDING_DP.dp
                )
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Allow historical data",
                    style = MaterialTheme.typography.titleSmall
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Without this permission, AI clients only see data " +
                    "recorded after you enabled the integration. Grant access " +
                    "so past records (steps, sleep, workouts, etc.) are also " +
                    "available.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onRequestHistoricalAccess,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Grant historical access")
            }
        }
    }
}

private const val HISTORICAL_CORNER_DP = 12
private const val HISTORICAL_H_PADDING_DP = 16
private const val HISTORICAL_V_PADDING_DP = 12

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun HealthConnectSetupPreview() {
    RouseContextTheme(darkTheme = true) {
        HealthConnectSetupScreen()
    }
}
