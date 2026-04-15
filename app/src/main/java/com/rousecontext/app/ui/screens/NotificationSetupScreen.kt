package com.rousecontext.app.ui.screens

import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rousecontext.app.R
import com.rousecontext.app.ui.components.FloatingSaveBar
import com.rousecontext.app.ui.components.PrivacyWarningCard
import com.rousecontext.app.ui.components.SectionHeader
import com.rousecontext.app.ui.components.SwitchRow
import com.rousecontext.app.ui.components.appBarColors
import com.rousecontext.app.ui.theme.RouseContextTheme
import com.rousecontext.app.ui.theme.SuccessGreen
import com.rousecontext.app.ui.viewmodels.NotificationSetupState
import com.rousecontext.app.ui.viewmodels.NotificationSetupViewModel

/**
 * Content-only variant used inside the persistent Scaffold in AppNavigation.
 */
@Composable
fun NotificationSetupContent(
    state: NotificationSetupState = NotificationSetupState(),
    mode: SetupMode = SetupMode.SETUP,
    isDirty: Boolean = false,
    onGrantAccess: () -> Unit = {},
    onRetentionChanged: (Int) -> Unit = {},
    onAllowActionsChanged: (Boolean) -> Unit = {},
    onEnable: () -> Unit = {},
    onSave: () -> Unit = {},
    onCancel: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    NotificationSetupBody(
        state = state,
        mode = mode,
        isDirty = isDirty,
        onGrantAccess = onGrantAccess,
        onRetentionChanged = onRetentionChanged,
        onAllowActionsChanged = onAllowActionsChanged,
        onEnable = onEnable,
        onSave = onSave,
        onCancel = onCancel,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSetupScreen(
    state: NotificationSetupState = NotificationSetupState(),
    mode: SetupMode = SetupMode.SETUP,
    isDirty: Boolean = false,
    onGrantAccess: () -> Unit = {},
    onRetentionChanged: (Int) -> Unit = {},
    onAllowActionsChanged: (Boolean) -> Unit = {},
    onEnable: () -> Unit = {},
    onSave: () -> Unit = {},
    onCancel: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_notification_setup_title)) },
                colors = appBarColors(),
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        NotificationSetupBody(
            state = state,
            mode = mode,
            isDirty = isDirty,
            onGrantAccess = onGrantAccess,
            onRetentionChanged = onRetentionChanged,
            onAllowActionsChanged = onAllowActionsChanged,
            onEnable = onEnable,
            onSave = onSave,
            onCancel = onCancel,
            modifier = Modifier.padding(padding)
        )
    }
}

@Composable
private fun NotificationSetupBody(
    state: NotificationSetupState,
    mode: SetupMode = SetupMode.SETUP,
    isDirty: Boolean = false,
    onGrantAccess: () -> Unit = {},
    onRetentionChanged: (Int) -> Unit = {},
    onAllowActionsChanged: (Boolean) -> Unit = {},
    onEnable: () -> Unit = {},
    onSave: () -> Unit = {},
    onCancel: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.screen_notification_setup_description),
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Privacy warning
            PrivacyWarningCard(
                text = stringResource(R.string.screen_notification_setup_privacy)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Permission status
            SectionHeader(stringResource(R.string.common_section_permission))

            if (state.permissionGranted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.common_granted),
                        modifier = Modifier.size(20.dp),
                        tint = SuccessGreen
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            R.string.screen_notification_setup_access_granted
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                OutlinedButton(
                    onClick = onGrantAccess,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.common_grant_access))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Retention picker
            SectionHeader(
                stringResource(R.string.screen_notification_setup_retention_heading)
            )

            RetentionDropdown(
                selectedDays = state.retentionDays,
                onSelected = onRetentionChanged
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Action toggle
            Card(modifier = Modifier.fillMaxWidth()) {
                SwitchRow(
                    title = stringResource(
                        R.string.screen_notification_setup_allow_actions_title
                    ),
                    subtitle = stringResource(
                        R.string.screen_notification_setup_allow_actions_subtitle
                    ),
                    checked = state.allowActions,
                    onCheckedChange = onAllowActionsChanged
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // In SETUP mode the "Enable" / "Cancel" buttons remain inline so
            // they participate in the onboarding flow. In SETTINGS mode they
            // are replaced by the pinned, dirty-state-aware FloatingSaveBar
            // (back navigation discards changes).
            if (mode == SetupMode.SETUP) {
                Button(
                    onClick = onEnable,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.permissionGranted
                ) {
                    Text(stringResource(R.string.common_enable))
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
        if (mode == SetupMode.SETTINGS) {
            FloatingSaveBar(
                visible = isDirty,
                onSave = onSave,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
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

@Composable
private fun formatRetention(days: Int): String = when (days) {
    1 -> stringResource(R.string.screen_notification_setup_retention_1_day)
    7 -> stringResource(R.string.screen_notification_setup_retention_1_week)
    30 -> stringResource(R.string.screen_notification_setup_retention_1_month)
    90 -> stringResource(R.string.screen_notification_setup_retention_3_months)
    else -> stringResource(R.string.screen_notification_setup_retention_days, days)
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
