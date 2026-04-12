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
import com.rousecontext.app.ui.components.appBarColors
import com.rousecontext.app.ui.theme.RouseContextTheme
import com.rousecontext.app.ui.theme.SuccessGreen

@Immutable
data class AuthorizationApprovalItem(val displayCode: String, val integration: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthorizationApprovalScreen(
    pendingRequests: List<AuthorizationApprovalItem> = emptyList(),
    onApprove: (String) -> Unit = {},
    onDeny: (String) -> Unit = {},
    onBack: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Approve Connection") },
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
        Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
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
