package com.rousecontext.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rousecontext.app.ui.components.ListDivider
import com.rousecontext.app.ui.components.ListRowWithIcon
import com.rousecontext.app.ui.components.SectionHeader
import com.rousecontext.app.ui.components.appBarColors
import com.rousecontext.app.ui.theme.RouseContextTheme
import com.rousecontext.mcp.health.RecordTypeRegistry

@Immutable
data class HealthPermission(val name: String, val granted: Boolean)

@Immutable
data class HealthConnectSettingsState(val permissions: List<HealthPermission> = emptyList())

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
                colors = appBarColors(),
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
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            SectionHeader("Permissions")

            val collapsedCount = 3
            var expanded by remember { mutableStateOf(false) }
            val showToggle = state.permissions.size > collapsedCount
            val visiblePermissions = if (expanded || !showToggle) {
                state.permissions
            } else {
                state.permissions.take(collapsedCount)
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    visiblePermissions.forEachIndexed { index, permission ->
                        PermissionRow(permission, onGrantPermission)
                        if (index < visiblePermissions.lastIndex) {
                            ListDivider()
                        }
                    }
                    AnimatedVisibility(
                        visible = expanded && showToggle,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        Column {
                            state.permissions.drop(collapsedCount)
                                .forEachIndexed { index, permission ->
                                    ListDivider()
                                    PermissionRow(permission, onGrantPermission)
                                }
                        }
                    }
                    if (showToggle) {
                        ListDivider()
                        TextButton(
                            onClick = { expanded = !expanded },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp)
                        ) {
                            Text(
                                if (expanded) {
                                    "Show less"
                                } else {
                                    "Show all (${state.permissions.size})"
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Permissions are enforced per session. " +
                    "Revoking access takes effect immediately.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PermissionRow(permission: HealthPermission, onGrantPermission: (String) -> Unit) {
    ListRowWithIcon(
        icon = if (permission.granted) {
            Icons.Default.CheckCircle
        } else {
            Icons.Default.RadioButtonUnchecked
        },
        iconTint = if (permission.granted) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outline
        }
    ) {
        Text(
            text = permission.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        if (!permission.granted) {
            TextButton(
                onClick = { onGrantPermission(permission.name) }
            ) {
                Text("Grant")
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
                permissions = RecordTypeRegistry.allTypes.mapIndexed { index, info ->
                    HealthPermission(
                        name = info.displayName,
                        granted = index < 3
                    )
                }
            )
        )
    }
}
