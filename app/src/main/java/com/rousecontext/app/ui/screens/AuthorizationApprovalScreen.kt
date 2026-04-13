package com.rousecontext.app.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rousecontext.app.ui.components.ErrorState
import com.rousecontext.app.ui.components.LoadingIndicator
import com.rousecontext.app.ui.components.appBarColors
import com.rousecontext.app.ui.theme.RouseContextTheme
import com.rousecontext.app.ui.theme.SuccessGreen

@Immutable
data class AuthorizationApprovalItem(val displayCode: String, val integration: String)

/**
 * UI state for the authorization approval screen.
 */
sealed interface AuthorizationApprovalUiState {
    data object Loading : AuthorizationApprovalUiState
    data class Loaded(val pendingRequests: List<AuthorizationApprovalItem>) :
        AuthorizationApprovalUiState
    data class Error(val message: String) : AuthorizationApprovalUiState
}

/**
 * Content-only variant used inside the persistent Scaffold in AppNavigation.
 *
 * Previously this screen nested its own Scaffold + TopAppBar inside the
 * persistent Scaffold from AppNavigation, which double-applied window insets
 * and produced a stale title (#68 renamed "Approve Connection" to
 * "Approve AI Client" but #64 later added the inner Scaffold with the old
 * string). Following the pattern used by every other screen, the Content
 * composable renders only the body; the wrapper Screen composable is kept
 * for @Preview usage.
 */
@Composable
fun AuthorizationApprovalContent(
    uiState: AuthorizationApprovalUiState,
    onApprove: (String) -> Unit = {},
    onDeny: (String) -> Unit = {},
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier.fillMaxSize()) {
        when (uiState) {
            is AuthorizationApprovalUiState.Loading -> LoadingIndicator()
            is AuthorizationApprovalUiState.Error -> ErrorState(
                message = uiState.message,
                onRetry = onRetry
            )
            is AuthorizationApprovalUiState.Loaded -> LoadedAuthorizationApproval(
                pendingRequests = uiState.pendingRequests,
                onApprove = onApprove,
                onDeny = onDeny
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthorizationApprovalScreen(
    pendingRequests: List<AuthorizationApprovalItem> = emptyList(),
    onApprove: (String) -> Unit = {},
    onDeny: (String) -> Unit = {},
    onBack: () -> Unit = {}
) {
    AuthorizationApprovalScreen(
        uiState = AuthorizationApprovalUiState.Loaded(pendingRequests),
        onApprove = onApprove,
        onDeny = onDeny,
        onBack = onBack
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthorizationApprovalScreen(
    uiState: AuthorizationApprovalUiState,
    onApprove: (String) -> Unit = {},
    onDeny: (String) -> Unit = {},
    onRetry: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Approve AI Client") },
                colors = appBarColors(),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        AuthorizationApprovalContent(
            uiState = uiState,
            onApprove = onApprove,
            onDeny = onDeny,
            onRetry = onRetry,
            modifier = Modifier.padding(padding)
        )
    }
}

@Composable
private fun LoadedAuthorizationApproval(
    pendingRequests: List<AuthorizationApprovalItem>,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit
) {
    if (pendingRequests.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "No Pending Requests",
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Authorization requests from AI clients will appear here.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        val request = pendingRequests.first()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (pendingRequests.size > 1) {
                Text(
                    text = "1 of ${pendingRequests.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            AuthorizationRequestCard(
                request = request,
                onApprove = onApprove,
                onDeny = onDeny
            )
        }
    }
}

@Composable
private fun AuthorizationRequestCard(
    request: AuthorizationApprovalItem,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = request.integration,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = request.displayCode,
                style = MaterialTheme.typography.displaySmall,
                fontFamily = FontFamily.Monospace
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Confirm this code matches the one shown by the AI client.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { onDeny(request.displayCode) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                    )
                ) {
                    Text("Deny")
                }
                Button(
                    onClick = { onApprove(request.displayCode) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SuccessGreen,
                        contentColor = Color.White
                    )
                ) {
                    Text("Approve")
                }
            }
        }
    }
}

// --- Previews ---

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun AuthorizationApprovalEmptyPreview() {
    RouseContextTheme(darkTheme = true) {
        AuthorizationApprovalScreen()
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun AuthorizationApprovalLoadingPreview() {
    RouseContextTheme(darkTheme = true) {
        AuthorizationApprovalScreen(uiState = AuthorizationApprovalUiState.Loading)
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun AuthorizationApprovalErrorPreview() {
    RouseContextTheme(darkTheme = true) {
        AuthorizationApprovalScreen(
            uiState = AuthorizationApprovalUiState.Error(
                "Could not load pending requests."
            )
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun AuthorizationApprovalSinglePreview() {
    RouseContextTheme(darkTheme = true) {
        AuthorizationApprovalScreen(
            pendingRequests = listOf(
                AuthorizationApprovalItem(
                    displayCode = "AB3X-9K2F",
                    integration = "Health Connect"
                )
            )
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun AuthorizationApprovalMultiplePreview() {
    RouseContextTheme(darkTheme = true) {
        AuthorizationApprovalScreen(
            pendingRequests = listOf(
                AuthorizationApprovalItem(
                    displayCode = "AB3X-9K2F",
                    integration = "Health Connect"
                ),
                AuthorizationApprovalItem(
                    displayCode = "7YMN-4HPQ",
                    integration = "Notifications"
                ),
                AuthorizationApprovalItem(
                    displayCode = "ZXCV-1234",
                    integration = "Usage Stats"
                )
            )
        )
    }
}

@Preview(
    showBackground = true,
    showSystemUi = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO
)
@Composable
fun AuthorizationApprovalWithRequestsLightPreview() {
    RouseContextTheme(darkTheme = false) {
        AuthorizationApprovalScreen(
            pendingRequests = listOf(
                AuthorizationApprovalItem(
                    displayCode = "AB3X-9K2F",
                    integration = "Health Connect"
                )
            )
        )
    }
}
