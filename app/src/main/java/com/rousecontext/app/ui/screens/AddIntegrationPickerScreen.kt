package com.rousecontext.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rousecontext.app.R
import com.rousecontext.app.ui.components.appBarColors
import com.rousecontext.app.ui.theme.RouseContextTheme

enum class PickerIntegrationState { AVAILABLE }

@Immutable
data class PickerIntegration(
    val id: String,
    val name: String,
    val description: String,
    val state: PickerIntegrationState
)

/**
 * Content-only variant used inside the persistent Scaffold in AppNavigation.
 */
@Composable
fun AddIntegrationPickerContent(
    integrations: List<PickerIntegration> = emptyList(),
    onSetUp: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = dimensionResource(R.dimen.spacing_lg))
    ) {
        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_sm)))
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(integrations) { integration ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = dimensionResource(R.dimen.spacing_xs))
                        .clickable { onSetUp(integration.id) },
                    border = BorderStroke(
                        1.5.dp,
                        MaterialTheme.colorScheme.primary
                    )
                ) {
                    Column(modifier = Modifier.padding(dimensionResource(R.dimen.spacing_lg))) {
                        Text(
                            text = integration.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_xs)))
                        Text(
                            text = integration.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddIntegrationPickerScreen(
    integrations: List<PickerIntegration> = emptyList(),
    onSetUp: (String) -> Unit = {},
    onCancel: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_add_integration_title)) },
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
        AddIntegrationPickerContent(
            integrations = integrations,
            onSetUp = onSetUp,
            modifier = Modifier.padding(padding)
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun AddIntegrationPickerPreview() {
    RouseContextTheme(darkTheme = true) {
        AddIntegrationPickerScreen(
            integrations = listOf(
                PickerIntegration(
                    id = "notifications",
                    name = "Notifications",
                    description = "Let AI clients read your notifications",
                    state = PickerIntegrationState.AVAILABLE
                ),
                PickerIntegration(
                    id = "outreach",
                    name = "Outreach",
                    description = "Let AI clients send messages on your behalf",
                    state = PickerIntegrationState.AVAILABLE
                ),
                PickerIntegration(
                    id = "usage",
                    name = "Usage Stats",
                    description = "Let AI see your app usage patterns and screen time",
                    state = PickerIntegrationState.AVAILABLE
                ),
                PickerIntegration(
                    id = "health",
                    name = "Health Connect",
                    description = "Share step count, heart rate, and sleep data with AI clients",
                    state = PickerIntegrationState.AVAILABLE
                )
            )
        )
    }
}
