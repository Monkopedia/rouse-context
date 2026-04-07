package com.rousecontext.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Schedule
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
import com.rousecontext.app.ui.viewmodels.OnboardingStep

sealed interface SettingUpVariant {
    data class FirstTime(val step: OnboardingStep = OnboardingStep.FIREBASE_AUTH) : SettingUpVariant
    data object Refreshing : SettingUpVariant
    data class RateLimited(val expectedDate: String) : SettingUpVariant
}

@Immutable
data class SettingUpState(
    val variant: SettingUpVariant = SettingUpVariant.FirstTime()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingUpScreen(state: SettingUpState = SettingUpState(), onCancel: () -> Unit = {}) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Setting Up") }, colors = appBarColors())
        }
    ) { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                when (state.variant) {
                    is SettingUpVariant.FirstTime, is SettingUpVariant.Refreshing -> {
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
                }

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = when (state.variant) {
                        is SettingUpVariant.FirstTime ->
                            "Setting up your device..."
                        is SettingUpVariant.Refreshing ->
                            "Your certificate is being refreshed."
                        is SettingUpVariant.RateLimited ->
                            "Certificate issuance is temporarily delayed."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                when (state.variant) {
                    is SettingUpVariant.FirstTime -> {
                        val step = state.variant.step
                        SetupStepRow(
                            label = "Authenticating",
                            done = step > OnboardingStep.FIREBASE_AUTH,
                            active = step == OnboardingStep.FIREBASE_AUTH
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        SetupStepRow(
                            label = "Registering with relay",
                            done = false,
                            active = step == OnboardingStep.RELAY_REGISTRATION
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "This usually takes a few seconds.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is SettingUpVariant.Refreshing -> {
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
                }

                Spacer(modifier = Modifier.height(32.dp))

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
}

@Composable
private fun SetupStepRow(label: String, done: Boolean, active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            done -> Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Complete",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            active -> CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = AmberAccent
            )
            else -> Icon(
                Icons.Default.Circle,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.outline
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (done || active) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun SettingUpFirstTimePreview() {
    RouseContextTheme(darkTheme = true) {
        SettingUpScreen(
            state = SettingUpState(SettingUpVariant.FirstTime(OnboardingStep.FIREBASE_AUTH))
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun SettingUpRefreshingPreview() {
    RouseContextTheme(darkTheme = true) {
        SettingUpScreen(state = SettingUpState(SettingUpVariant.Refreshing))
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun SettingUpRateLimitedPreview() {
    RouseContextTheme(darkTheme = true) {
        SettingUpScreen(state = SettingUpState(SettingUpVariant.RateLimited("Apr 11")))
    }
}
