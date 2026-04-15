package com.rousecontext.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rousecontext.app.R
import com.rousecontext.app.ui.components.appBarColors
import com.rousecontext.app.ui.theme.RouseContextTheme
import com.rousecontext.app.ui.theme.SuccessGreen
import com.rousecontext.integrations.health.RecordCategory
import com.rousecontext.integrations.health.RecordTypeRegistry

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
    grantedRecordTypes: Set<String> = emptySet(),
    modifier: Modifier = Modifier
) {
    HealthConnectSetupBody(
        mode = mode,
        onGrantAccess = onGrantAccess,
        onCancel = onCancel,
        historicalAccessGranted = historicalAccessGranted,
        onRequestHistoricalAccess = onRequestHistoricalAccess,
        grantedRecordTypes = grantedRecordTypes,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthConnectSetupScreen(onGrantAccess: () -> Unit = {}, onCancel: () -> Unit = {}) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.screen_health_connect_setup_title))
                },
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
        HealthConnectSetupBody(
            onGrantAccess = onGrantAccess,
            onCancel = onCancel,
            historicalAccessGranted = false,
            onRequestHistoricalAccess = {},
            grantedRecordTypes = emptySet(),
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
    grantedRecordTypes: Set<String>,
    modifier: Modifier = Modifier
) {
    val typesByCategory = RecordTypeRegistry.allTypes
        .groupBy { it.category }
        .toSortedMap(compareBy { it.ordinal })

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.screen_health_connect_setup_description),
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.screen_health_connect_setup_request_list_heading),
            style = MaterialTheme.typography.titleSmall
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Per-category expanded state. Default: all collapsed, so the full
        // permission list does not overwhelm the user on first view (#60).
        val expandedState = remember { mutableStateMapOf<RecordCategory, Boolean>() }

        // Per-record-type granted indicators are only meaningful in SETTINGS
        // mode (#99). In the initial SETUP flow, no permissions have been
        // granted yet by definition, so the list acts as a plain preview of
        // what will be requested.
        val showGrantedIndicators = mode == SetupMode.SETTINGS

        typesByCategory.forEach { (category, types) ->
            val expanded = expandedState[category] ?: false
            val grantedInCategory = types.count { it.name in grantedRecordTypes }
            CategoryHeader(
                category = category,
                grantedCount = grantedInCategory,
                totalCount = types.size,
                showGrantedFraction = showGrantedIndicators,
                expanded = expanded,
                onToggle = { expandedState[category] = !expanded }
            )
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    types.forEach { recordType ->
                        val isGranted = recordType.name in grantedRecordTypes
                        Row(
                            modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            if (showGrantedIndicators) {
                                Icon(
                                    imageVector = if (isGranted) {
                                        Icons.Filled.CheckCircle
                                    } else {
                                        Icons.Outlined.RadioButtonUnchecked
                                    },
                                    contentDescription = if (isGranted) {
                                        stringResource(
                                            R.string.screen_health_connect_setup_granted_cd,
                                            recordType.displayName
                                        )
                                    } else {
                                        stringResource(
                                            R.string.screen_health_connect_setup_not_granted_cd,
                                            recordType.displayName
                                        )
                                    },
                                    tint = if (isGranted) {
                                        SuccessGreen
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            } else {
                                Text(
                                    text = stringResource(
                                        R.string.screen_health_connect_setup_bullet
                                    ),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
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
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(
                R.string.screen_health_connect_setup_system_settings_note
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.common_audit_log_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(20.dp))

        HistoricalAccessSection(
            granted = historicalAccessGranted,
            onRequestHistoricalAccess = onRequestHistoricalAccess
        )

        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(32.dp))

        val buttonText = if (mode == SetupMode.SETTINGS) {
            stringResource(R.string.screen_health_connect_setup_manage)
        } else {
            stringResource(R.string.screen_health_connect_setup_grant_all)
        }

        Button(
            onClick = onGrantAccess,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(buttonText)
        }

        // The Cancel button only appears during initial SETUP. In SETTINGS
        // mode the user dismisses via the top-bar back arrow (the "Update
        // Health Access" action is a system-permission launch, not an
        // in-app save — there is no dirty state to discard). See #59.
        if (mode == SetupMode.SETUP) {
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
                    text = stringResource(
                        R.string.screen_health_connect_setup_historical_enabled_title
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = stringResource(
                        R.string.screen_health_connect_setup_historical_enabled_description
                    ),
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
                    text = stringResource(
                        R.string.screen_health_connect_setup_historical_disabled_title
                    ),
                    style = MaterialTheme.typography.titleSmall
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(
                    R.string.screen_health_connect_setup_historical_disabled_description
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onRequestHistoricalAccess,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.screen_health_connect_setup_grant_historical))
            }
        }
    }
}

@Composable
private fun CategoryHeader(
    category: RecordCategory,
    grantedCount: Int,
    totalCount: Int,
    showGrantedFraction: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val allGranted = grantedCount == totalCount
    val countText = if (showGrantedFraction) {
        stringResource(
            R.string.screen_health_connect_setup_count_fraction,
            grantedCount,
            totalCount
        )
    } else {
        stringResource(
            R.string.screen_health_connect_setup_count_total,
            totalCount
        )
    }
    val countColor = if (!showGrantedFraction || allGranted) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        // Draw the eye when some types in the category are not yet granted.
        // #99: use the error tint so it is visible in both light and dark
        // themes without competing with the primary heading color.
        MaterialTheme.colorScheme.error
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .defaultMinSize(minHeight = 48.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = category.value.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = countText,
            style = MaterialTheme.typography.bodyMedium,
            color = countColor
        )
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (expanded) {
                stringResource(
                    R.string.screen_health_connect_setup_collapse_content_description,
                    category.toString()
                )
            } else {
                stringResource(
                    R.string.screen_health_connect_setup_expand_content_description,
                    category.toString()
                )
            },
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
