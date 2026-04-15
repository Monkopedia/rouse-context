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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rousecontext.app.R
import com.rousecontext.app.ui.components.appBarColors
import com.rousecontext.app.ui.theme.AmberAccent
import com.rousecontext.app.ui.theme.RouseContextTheme

enum class NotificationMode {
    SUMMARY,
    EACH_USAGE,
    SUPPRESS
}

@Immutable
data class NotificationPreferencesState(
    val selectedMode: NotificationMode = NotificationMode.SUMMARY
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationPreferencesScreen(
    state: NotificationPreferencesState = NotificationPreferencesState(),
    onModeSelected: (NotificationMode) -> Unit = {},
    onContinue: () -> Unit = {},
    onRequestNotificationPermission: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    var selected by remember(state.selectedMode) { mutableStateOf(state.selectedMode) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.screen_notification_preferences_title))
                },
                colors = appBarColors(),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
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
            Spacer(modifier = Modifier.height(24.dp))

            Icon(
                imageVector = Icons.Outlined.Notifications,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = AmberAccent
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.screen_notification_preferences_prompt),
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.height(24.dp))

            Column(modifier = Modifier.selectableGroup()) {
                NotificationOption(
                    title = stringResource(
                        R.string.screen_notification_preferences_summary_title
                    ),
                    description = stringResource(
                        R.string.screen_notification_preferences_summary_description
                    ),
                    selected = selected == NotificationMode.SUMMARY,
                    onClick = {
                        selected = NotificationMode.SUMMARY
                        onModeSelected(NotificationMode.SUMMARY)
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))
                NotificationOption(
                    title = stringResource(
                        R.string.screen_notification_preferences_each_title
                    ),
                    description = stringResource(
                        R.string.screen_notification_preferences_each_description
                    ),
                    selected = selected == NotificationMode.EACH_USAGE,
                    onClick = {
                        selected = NotificationMode.EACH_USAGE
                        onModeSelected(NotificationMode.EACH_USAGE)
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))
                NotificationOption(
                    title = stringResource(
                        R.string.screen_notification_preferences_suppress_title
                    ),
                    description = stringResource(
                        R.string.screen_notification_preferences_suppress_description
                    ),
                    selected = selected == NotificationMode.SUPPRESS,
                    onClick = {
                        selected = NotificationMode.SUPPRESS
                        onModeSelected(NotificationMode.SUPPRESS)
                    }
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    // Trigger the OS permission prompt before continuing so
                    // the user grants notifications inline as part of
                    // onboarding. The screen does not block on the result;
                    // onContinue is invoked regardless so denial does not
                    // strand the user. Issue #93.
                    onRequestNotificationPermission()
                    onContinue()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            ) {
                Text(stringResource(R.string.common_continue))
            }
        }
    }
}

@Composable
private fun NotificationOption(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            )
            .padding(vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = null)
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun NotificationPreferencesPreview() {
    RouseContextTheme(darkTheme = true) {
        NotificationPreferencesScreen()
    }
}
