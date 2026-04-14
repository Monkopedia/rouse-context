package com.rousecontext.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rousecontext.app.ui.components.appBarColors
import com.rousecontext.app.ui.theme.AmberAccent
import com.rousecontext.app.ui.theme.RouseContextTheme

sealed interface SettingUpVariant {
    /** Shown when integration setup is waiting for background device registration. */
    data object Registering : SettingUpVariant
    data object Requesting : SettingUpVariant
    data class RateLimited(val expectedDate: String) : SettingUpVariant

    /**
     * Terminal failure surface. Paired with a Retry button in the UI so the user
     * can re-invoke the failed step without backing out of setup (#108).
     */
    data class Failed(val message: String) : SettingUpVariant
}

@Immutable
data class SettingUpState(val variant: SettingUpVariant = SettingUpVariant.Requesting)

/**
 * Content-only variant used inside the persistent Scaffold in AppNavigation.
 *
 * [onRetry] is only shown for [SettingUpVariant.Failed]. When null on a Failed
 * variant, the button is hidden — but callers from the setup flow should always
 * supply one.
 */
@Composable
fun SettingUpContent(
    state: SettingUpState = SettingUpState(),
    onCancel: () -> Unit = {},
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    SettingUpBody(state = state, onCancel = onCancel, onRetry = onRetry, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingUpScreen(
    state: SettingUpState = SettingUpState(),
    onCancel: () -> Unit = {},
    onRetry: (() -> Unit)? = null
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Setting Up") }, colors = appBarColors())
        }
    ) { padding ->
        SettingUpBody(
            state = state,
            onCancel = onCancel,
            onRetry = onRetry,
            modifier = Modifier.padding(padding)
        )
    }
}

@Composable
private fun SettingUpBody(
    state: SettingUpState,
    onCancel: () -> Unit = {},
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            SettingUpLeadingGlyph(state.variant)

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = when (state.variant) {
                    is SettingUpVariant.Registering ->
                        "Registering your device..."
                    is SettingUpVariant.Requesting ->
                        "Requesting your certificate..."
                    is SettingUpVariant.RateLimited ->
                        "Certificate issuance is temporarily delayed."
                    is SettingUpVariant.Failed ->
                        "Setup didn't finish."
                },
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            when (state.variant) {
                is SettingUpVariant.Registering -> {
                    Text(
                        text = "This usually takes a few seconds.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is SettingUpVariant.Requesting -> {
                    Text(
                        text = "This usually takes about 30 seconds.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is SettingUpVariant.RateLimited -> {
                    Text(
                        text = "Will retry on ${state.variant.expectedDate}.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
                is SettingUpVariant.Failed -> {
                    Text(
                        text = state.variant.message,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            if (state.variant is SettingUpVariant.Failed && onRetry != null) {
                Button(onClick = onRetry) {
                    Text("Retry")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            TextButton(onClick = onCancel) {
                Text(
                    when (state.variant) {
                        is SettingUpVariant.RateLimited -> "Dismiss"
                        else -> "Cancel"
                    }
                )
            }
        }
    }
}

@Composable
private fun SettingUpLeadingGlyph(variant: SettingUpVariant) {
    when (variant) {
        is SettingUpVariant.Registering,
        is SettingUpVariant.Requesting -> {
            CircularProgressIndicator(
                color = AmberAccent,
                trackColor = AmberAccent.copy(alpha = 0.35f),
                modifier = Modifier.size(64.dp),
                strokeWidth = 5.dp
            )
        }
        is SettingUpVariant.RateLimited -> {
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = AmberAccent
            )
        }
        is SettingUpVariant.Failed -> {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun SettingUpRegisteringPreview() {
    RouseContextTheme(darkTheme = true) {
        SettingUpScreen(
            state = SettingUpState(SettingUpVariant.Registering)
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun SettingUpRequestingPreview() {
    RouseContextTheme(darkTheme = true) {
        SettingUpScreen(state = SettingUpState(SettingUpVariant.Requesting))
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun SettingUpRateLimitedPreview() {
    RouseContextTheme(darkTheme = true) {
        SettingUpScreen(state = SettingUpState(SettingUpVariant.RateLimited("Apr 11")))
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun SettingUpFailedPreview() {
    RouseContextTheme(darkTheme = true) {
        SettingUpScreen(
            state = SettingUpState(
                SettingUpVariant.Failed(
                    "Couldn't register integration with relay. Try again."
                )
            ),
            onRetry = {}
        )
    }
}
