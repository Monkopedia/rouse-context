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
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.rousecontext.app.R
import com.rousecontext.app.ui.components.PrivacyWarningCard
import com.rousecontext.app.ui.components.SectionHeader
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
                title = { Text(stringResource(R.string.screen_usage_setup_title)) },
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
            .padding(horizontal = dimensionResource(R.dimen.spacing_lg))
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_lg)))

        Text(
            text = stringResource(R.string.screen_usage_setup_description),
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_lg)))

        // Privacy warning
        PrivacyWarningCard(
            text = stringResource(R.string.screen_usage_setup_privacy)
        )

        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_xl)))

        // Permission status
        SectionHeader(stringResource(R.string.common_section_permission))

        if (state.permissionGranted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = stringResource(R.string.common_granted),
                    modifier = Modifier.size(dimensionResource(R.dimen.icon_size_sm)),
                    tint = SuccessGreen
                )
                Spacer(modifier = Modifier.width(dimensionResource(R.dimen.spacing_sm)))
                Text(
                    text = stringResource(R.string.screen_usage_setup_access_granted),
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

        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_md)))

        Text(
            text = stringResource(R.string.common_audit_log_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.weight(1f))

        // Usage Stats has no in-app editable state in SETTINGS mode — the
        // only control is the system-level permission grant, which is shown
        // inline above. So the "Enable"/"Cancel" buttons only appear during
        // the initial SETUP flow; in SETTINGS mode the user dismisses via
        // the top-bar back arrow. See #59.
        if (mode == SetupMode.SETUP) {
            Button(
                onClick = onEnable,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.permissionGranted
            ) {
                Text(stringResource(R.string.common_enable))
            }

            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_sm)))

            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.common_cancel))
            }
        }

        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_xl)))
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
