package com.rousecontext.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rousecontext.app.R
import com.rousecontext.app.ui.components.ListDivider
import com.rousecontext.app.ui.components.ListRow
import com.rousecontext.app.ui.components.SectionHeader
import com.rousecontext.app.ui.components.appBarColors
import com.rousecontext.app.ui.navigation.ConfigureNavBar
import com.rousecontext.app.ui.theme.RouseContextTheme

@Immutable
data class AuthorizedClient(val name: String, val authorizedDate: String, val lastUsed: String)

@Immutable
data class IntegrationManageState(
    val integrationName: String = "Health Connect",
    val status: IntegrationStatus = IntegrationStatus.ACTIVE,
    val url: String = "",
    val recentActivity: List<AuditEntry> = emptyList(),
    val authorizedClients: List<AuthorizedClient> = emptyList()
)

/**
 * Content-only variant used inside the persistent Scaffold in AppNavigation.
 * Configures the nav bar with integration name + status badge.
 */
@Composable
fun IntegrationManageContent(
    state: IntegrationManageState = IntegrationManageState(),
    onAddClient: () -> Unit = {},
    onViewAllActivity: () -> Unit = {},
    onViewAllClients: () -> Unit = {},
    onRevokeClient: (String) -> Unit = {},
    onEntryClick: (Long) -> Unit = {},
    onSettings: () -> Unit = {},
    onDisable: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    ConfigureNavBar(
        title = state.integrationName,
        showBackButton = true,
        showBottomBar = false,
        showTopBar = true,
        onBackPressed = onBack,
        titleContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(state.integrationName)
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onSettings) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(
                            R.string.screen_integration_manage_settings_content_description
                        )
                    )
                }
            }
        }
    )

    IntegrationManageBody(
        state = state,
        onAddClient = onAddClient,
        onViewAllActivity = onViewAllActivity,
        onViewAllClients = onViewAllClients,
        onRevokeClient = onRevokeClient,
        onEntryClick = onEntryClick,
        onDisable = onDisable
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntegrationManageScreen(
    state: IntegrationManageState = IntegrationManageState(),
    onAddClient: () -> Unit = {},
    onViewAllActivity: () -> Unit = {},
    onViewAllClients: () -> Unit = {},
    onRevokeClient: (String) -> Unit = {},
    onEntryClick: (Long) -> Unit = {},
    onSettings: () -> Unit = {},
    onDisable: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                colors = appBarColors(),
                title = { Text(state.integrationName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(
                                R.string.screen_integration_manage_settings_content_description
                            )
                        )
                    }
                }
            )
        }
    ) { padding ->
        IntegrationManageBody(
            state = state,
            onAddClient = onAddClient,
            onViewAllActivity = onViewAllActivity,
            onViewAllClients = onViewAllClients,
            onRevokeClient = onRevokeClient,
            onEntryClick = onEntryClick,
            onDisable = onDisable,
            modifier = Modifier.padding(padding)
        )
    }
}

@Composable
private fun IntegrationManageBody(
    state: IntegrationManageState,
    onAddClient: () -> Unit = {},
    onViewAllActivity: () -> Unit = {},
    onViewAllClients: () -> Unit = {},
    onRevokeClient: (String) -> Unit = {},
    onEntryClick: (Long) -> Unit = {},
    onDisable: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val maxVisibleClients = 4
    val maxVisibleActivity = 5

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = dimensionResource(R.dimen.spacing_lg))
    ) {
        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_md)))

        // -- Authorized Clients (top) --
        SectionHeader(stringResource(R.string.screen_integration_manage_authorized_clients))

        if (state.authorizedClients.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(dimensionResource(R.dimen.spacing_xl)),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.People,
                        contentDescription = null,
                        modifier = Modifier.size(dimensionResource(R.dimen.spacing_xxl)),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_sm)))
                    Text(
                        text = stringResource(
                            R.string.screen_integration_manage_waiting_title
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_xs)))
                    Text(
                        text = stringResource(
                            R.string.screen_integration_manage_waiting_subtitle
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            val visibleClients = state.authorizedClients.take(maxVisibleClients)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    visibleClients.forEachIndexed { index, client ->
                        ListRow {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    client.name,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    stringResource(
                                        R.string.screen_integration_manage_client_meta,
                                        client.authorizedDate,
                                        client.lastUsed
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(
                                onClick = { onRevokeClient(client.name) }
                            ) {
                                Text(
                                    stringResource(R.string.screen_integration_manage_revoke),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        if (index < visibleClients.lastIndex) {
                            ListDivider()
                        }
                    }
                }
            }
        }

        // View all / Add Client row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (state.authorizedClients.size > maxVisibleClients) {
                Arrangement.SpaceBetween
            } else {
                Arrangement.End
            }
        ) {
            if (state.authorizedClients.size > maxVisibleClients) {
                TextButton(onClick = onViewAllClients) {
                    Text(
                        stringResource(
                            R.string.screen_integration_manage_view_all_count,
                            state.authorizedClients.size
                        )
                    )
                }
            }
            TextButton(onClick = onAddClient) {
                Icon(
                    imageVector = Icons.Default.PersonAdd,
                    contentDescription = null,
                    modifier = Modifier.size(dimensionResource(R.dimen.spacing_lg))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.screen_integration_manage_add_client))
            }
        }

        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_sm)))

        // -- Recent Activity --
        if (state.status != IntegrationStatus.PENDING) {
            SectionHeader(stringResource(R.string.screen_integration_manage_recent_activity))

            if (state.recentActivity.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(dimensionResource(R.dimen.spacing_xl)),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            modifier = Modifier.size(dimensionResource(R.dimen.spacing_xxl)),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_sm)))
                        Text(
                            text = stringResource(
                                R.string.screen_integration_manage_no_recent_activity
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                val visibleActivity = state.recentActivity.take(maxVisibleActivity)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        visibleActivity.forEachIndexed { index, entry ->
                            ListRow(
                                onClick = { onEntryClick(entry.id) }
                            ) {
                                Text(
                                    entry.toolName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    entry.time,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(
                                    modifier = Modifier.width(dimensionResource(R.dimen.spacing_md))
                                )
                                DurationText(entry.durationMs)
                            }
                            if (index < visibleActivity.lastIndex) {
                                ListDivider()
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onViewAllActivity) {
                        Text(stringResource(R.string.screen_integration_manage_view_all))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Disable button (solid, error colored)
        androidx.compose.material3.Button(
            onClick = onDisable,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            )
        ) {
            Text(
                if (state.status == IntegrationStatus.PENDING) {
                    stringResource(R.string.screen_integration_manage_cancel_setup)
                } else {
                    stringResource(R.string.screen_integration_manage_disable)
                }
            )
        }
        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_lg)))
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun IntegrationManageActivePreview() {
    RouseContextTheme(darkTheme = true) {
        IntegrationManageScreen(
            state = IntegrationManageState(
                status = IntegrationStatus.ACTIVE,
                recentActivity = listOf(
                    AuditEntry("10:32 AM", "get_steps", 142),
                    AuditEntry("10:31 AM", "get_sleep", 89),
                    AuditEntry("Apr 7, 3:15 PM", "get_heart_rate", 1250)
                ),
                authorizedClients = listOf(
                    AuthorizedClient("Claude Desktop", "Apr 2", "2 hours ago"),
                    AuthorizedClient("Cursor", "Apr 3", "just now")
                )
            )
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun IntegrationManagePendingPreview() {
    RouseContextTheme(darkTheme = true) {
        IntegrationManageScreen(
            state = IntegrationManageState(
                status = IntegrationStatus.PENDING
            )
        )
    }
}
