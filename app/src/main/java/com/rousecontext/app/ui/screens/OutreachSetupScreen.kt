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
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.rousecontext.app.R
import com.rousecontext.app.ui.components.FloatingSaveBar
import com.rousecontext.app.ui.components.SectionHeader
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
    isDirty: Boolean = false,
    onDndToggled: (Boolean) -> Unit = {},
    onGrantDnd: () -> Unit = {},
    onDirectLaunchToggled: (Boolean) -> Unit = {},
    onGrantOverlay: () -> Unit = {},
    onEnable: () -> Unit = {},
    onSave: () -> Unit = {},
    onCancel: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    OutreachSetupBody(
        state = state,
        mode = mode,
        isDirty = isDirty,
        onDndToggled = onDndToggled,
        onGrantDnd = onGrantDnd,
        onDirectLaunchToggled = onDirectLaunchToggled,
        onGrantOverlay = onGrantOverlay,
        onEnable = onEnable,
        onSave = onSave,
        onCancel = onCancel,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutreachSetupScreen(
    state: OutreachSetupState = OutreachSetupState(),
    mode: SetupMode = SetupMode.SETUP,
    isDirty: Boolean = false,
    onDndToggled: (Boolean) -> Unit = {},
    onGrantDnd: () -> Unit = {},
    onDirectLaunchToggled: (Boolean) -> Unit = {},
    onGrantOverlay: () -> Unit = {},
    onEnable: () -> Unit = {},
    onSave: () -> Unit = {},
    onCancel: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_outreach_setup_title)) },
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
        OutreachSetupBody(
            state = state,
            mode = mode,
            isDirty = isDirty,
            onDndToggled = onDndToggled,
            onGrantDnd = onGrantDnd,
            onDirectLaunchToggled = onDirectLaunchToggled,
            onGrantOverlay = onGrantOverlay,
            onEnable = onEnable,
            onSave = onSave,
            onCancel = onCancel,
            modifier = Modifier.padding(padding)
        )
    }
}

@Composable
private fun OutreachSetupBody(
    state: OutreachSetupState,
    mode: SetupMode = SetupMode.SETUP,
    isDirty: Boolean = false,
    onDndToggled: (Boolean) -> Unit = {},
    onGrantDnd: () -> Unit = {},
    onDirectLaunchToggled: (Boolean) -> Unit = {},
    onGrantOverlay: () -> Unit = {},
    onEnable: () -> Unit = {},
    onSave: () -> Unit = {},
    onCancel: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = dimensionResource(R.dimen.spacing_lg))
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_lg)))

            Text(
                text = stringResource(R.string.screen_outreach_setup_description),
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_lg)))

            Text(
                text = stringResource(R.string.screen_outreach_setup_no_permissions_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_md)))

            Text(
                text = stringResource(R.string.screen_outreach_setup_audit_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_xl)))

            // DND section
            SectionHeader(stringResource(R.string.screen_outreach_setup_dnd_section))

            Card(modifier = Modifier.fillMaxWidth()) {
                SwitchRow(
                    title = stringResource(R.string.screen_outreach_setup_dnd_toggle_title),
                    subtitle = stringResource(
                        R.string.screen_outreach_setup_dnd_toggle_subtitle
                    ),
                    checked = state.dndToggled,
                    onCheckedChange = onDndToggled,
                    expandedContent = {
                        Column(
                            modifier = Modifier.padding(
                                start = dimensionResource(R.dimen.spacing_lg),
                                end = dimensionResource(R.dimen.spacing_lg),
                                bottom = dimensionResource(R.dimen.spacing_lg)
                            )
                        ) {
                            if (state.dndPermissionGranted) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription =
                                        stringResource(R.string.common_granted),
                                        modifier = Modifier.size(
                                            dimensionResource(R.dimen.icon_size_sm)
                                        ),
                                        tint = SuccessGreen
                                    )
                                    Spacer(
                                        modifier = Modifier.width(
                                            dimensionResource(R.dimen.spacing_sm)
                                        )
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.common_permission_granted
                                        ),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            } else {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor =
                                        MaterialTheme.colorScheme.secondaryContainer
                                    )
                                ) {
                                    Text(
                                        text = stringResource(
                                            R.string.screen_outreach_setup_dnd_explainer
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(
                                            dimensionResource(R.dimen.spacing_md)
                                        )
                                    )
                                }

                                Spacer(
                                    modifier = Modifier.height(
                                        dimensionResource(R.dimen.spacing_sm)
                                    )
                                )

                                OutlinedButton(
                                    onClick = onGrantDnd,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        stringResource(
                                            R.string.screen_outreach_setup_grant_dnd
                                        )
                                    )
                                }
                            }
                        }
                    }
                )
            }

            if (state.directLaunchApplicable) {
                Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_xl)))

                SectionHeader(stringResource(R.string.screen_outreach_setup_background_section))

                Card(modifier = Modifier.fillMaxWidth()) {
                    SwitchRow(
                        title = stringResource(
                            R.string.screen_outreach_setup_background_toggle_title
                        ),
                        subtitle = stringResource(
                            R.string.screen_outreach_setup_background_toggle_subtitle
                        ),
                        checked = state.directLaunchEnabled,
                        onCheckedChange = onDirectLaunchToggled,
                        expandedContent = {
                            Column(
                                modifier = Modifier.padding(
                                    start = dimensionResource(R.dimen.spacing_lg),
                                    end = dimensionResource(R.dimen.spacing_lg),
                                    bottom = dimensionResource(R.dimen.spacing_lg)
                                )
                            ) {
                                if (state.overlayPermissionGranted) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription =
                                            stringResource(R.string.common_granted),
                                            modifier = Modifier.size(
                                                dimensionResource(R.dimen.icon_size_sm)
                                            ),
                                            tint = SuccessGreen
                                        )
                                        Spacer(
                                            modifier = Modifier.width(
                                                dimensionResource(R.dimen.spacing_sm)
                                            )
                                        )
                                        Text(
                                            text = stringResource(
                                                R.string.common_permission_granted
                                            ),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                } else {
                                    Card(
                                        colors = CardDefaults.cardColors(
                                            containerColor =
                                            MaterialTheme.colorScheme.secondaryContainer
                                        )
                                    ) {
                                        Text(
                                            text = stringResource(
                                                R.string.screen_outreach_setup_background_explainer
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color =
                                            MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.padding(
                                                dimensionResource(R.dimen.spacing_md)
                                            )
                                        )
                                    }

                                    Spacer(
                                        modifier = Modifier.height(
                                            dimensionResource(R.dimen.spacing_sm)
                                        )
                                    )

                                    OutlinedButton(
                                        onClick = onGrantOverlay,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            stringResource(
                                                R.string.screen_outreach_setup_grant_background
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // In SETUP mode keep the inline Enable / Cancel buttons for the
            // onboarding flow. In SETTINGS mode they are replaced by the
            // pinned, dirty-state-aware FloatingSaveBar (back nav discards).
            if (mode == SetupMode.SETUP) {
                Button(
                    onClick = onEnable,
                    modifier = Modifier.fillMaxWidth()
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
        if (mode == SetupMode.SETTINGS) {
            FloatingSaveBar(
                visible = isDirty,
                onSave = onSave,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
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
