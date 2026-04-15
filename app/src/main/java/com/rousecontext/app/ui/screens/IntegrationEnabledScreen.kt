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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rousecontext.app.R
import com.rousecontext.app.ui.components.appBarColors
import com.rousecontext.app.ui.theme.AmberAccent
import com.rousecontext.app.ui.theme.RouseContextTheme

/**
 * Tracks the lifecycle of the MCP URL lookup so the "Add this URL to your
 * AI client" card can render the right affordance in each state (#164):
 *  - [Loading]   -- suspend lookup still in flight.
 *  - [Ready]     -- URL resolved; card shows the URL plus the copy button.
 *  - [Unavailable] -- subdomain or per-integration secret missing; card
 *    shows a diagnostic placeholder plus a Retry action instead of an
 *    empty box.
 */
@Immutable
sealed interface IntegrationEnabledUrlState {
    data object Loading : IntegrationEnabledUrlState
    data class Ready(val url: String) : IntegrationEnabledUrlState
    data object Unavailable : IntegrationEnabledUrlState
}

@Immutable
data class IntegrationEnabledState(
    val integrationName: String = "Health Connect",
    val url: String = "",
    val urlState: IntegrationEnabledUrlState? = null
) {
    /**
     * Effective url state: prefers the explicit [urlState] if the caller
     * provided one, otherwise derives from [url] for backwards-compat with
     * screenshot/preview callers that pass a plain URL string.
     */
    val effectiveUrlState: IntegrationEnabledUrlState
        get() = urlState ?: if (url.isEmpty()) {
            IntegrationEnabledUrlState.Loading
        } else {
            IntegrationEnabledUrlState.Ready(url)
        }
}

/**
 * Content-only variant used inside the persistent Scaffold in AppNavigation.
 */
@Composable
fun IntegrationEnabledContent(
    state: IntegrationEnabledState = IntegrationEnabledState(),
    onCopyUrl: () -> Unit = {},
    onCancel: () -> Unit = {},
    onRetryUrl: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    IntegrationEnabledBody(
        state = state,
        onCopyUrl = onCopyUrl,
        onCancel = onCancel,
        onRetryUrl = onRetryUrl,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntegrationEnabledScreen(
    state: IntegrationEnabledState = IntegrationEnabledState(),
    onCopyUrl: () -> Unit = {},
    onCancel: () -> Unit = {},
    onRetryUrl: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            R.string.screen_integration_enabled_title,
                            state.integrationName
                        )
                    )
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
        IntegrationEnabledBody(
            state = state,
            onCopyUrl = onCopyUrl,
            onCancel = onCancel,
            onRetryUrl = onRetryUrl,
            modifier = Modifier.padding(padding)
        )
    }
}

@Composable
private fun IntegrationEnabledBody(
    state: IntegrationEnabledState,
    onCopyUrl: () -> Unit = {},
    onCancel: () -> Unit = {},
    onRetryUrl: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(dimensionResource(R.dimen.spacing_xl)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_xxl)))

            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_lg)))

            Text(
                text = stringResource(
                    R.string.screen_integration_enabled_heading,
                    state.integrationName
                ),
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_xl)))

            Text(
                text = stringResource(R.string.screen_integration_enabled_add_url),
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_md)))

            IntegrationEnabledUrlCard(
                urlState = state.effectiveUrlState,
                onCopyUrl = onCopyUrl,
                onRetryUrl = onRetryUrl
            )

            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_xl)))

            Text(
                text = stringResource(R.string.screen_integration_enabled_approval_hint),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_xl)))

            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(dimensionResource(R.dimen.spacing_xl)),
                    color = AmberAccent,
                    strokeWidth = 2.5.dp
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.screen_integration_enabled_waiting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.screen_integration_enabled_finish_later))
            }
        }
    }
}

/**
 * URL-state-aware card body (#164). Keeps the previous "URL + copy button"
 * row as the happy path, and adds an explicit affordance for the two
 * degenerate states so users never see an empty card again:
 *  - Loading     -- spinner + "Preparing URL..."
 *  - Unavailable -- diagnostic text + Retry button
 */
@Composable
private fun IntegrationEnabledUrlCard(
    urlState: IntegrationEnabledUrlState,
    onCopyUrl: () -> Unit,
    onRetryUrl: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        when (urlState) {
            is IntegrationEnabledUrlState.Ready -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(dimensionResource(R.dimen.spacing_lg)),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = urlState.url,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onCopyUrl) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = stringResource(
                                R.string.screen_integration_enabled_copy_url_content_description
                            )
                        )
                    }
                }
            }
            is IntegrationEnabledUrlState.Loading -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(dimensionResource(R.dimen.spacing_lg)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(
                            dimensionResource(R.dimen.spacing_lg)
                        ),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(dimensionResource(R.dimen.spacing_md)))
                    Text(
                        text = stringResource(
                            R.string.screen_integration_enabled_url_preparing
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            is IntegrationEnabledUrlState.Unavailable -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(dimensionResource(R.dimen.spacing_lg)),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(
                            R.string.screen_integration_enabled_url_unavailable
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onRetryUrl) {
                        Text(
                            stringResource(
                                R.string.screen_integration_enabled_url_retry
                            )
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun IntegrationEnabledPreview() {
    RouseContextTheme(darkTheme = true) {
        IntegrationEnabledScreen()
    }
}
