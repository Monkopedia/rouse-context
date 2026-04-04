package com.rousecontext.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassEmpty
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
import com.rousecontext.app.ui.theme.RouseContextTheme

sealed interface SettingUpVariant {
    data object FirstTime : SettingUpVariant
    data object Refreshing : SettingUpVariant
    data class RateLimited(val expectedDate: String) : SettingUpVariant
}

@Immutable
data class SettingUpState(
    val variant: SettingUpVariant = SettingUpVariant.FirstTime
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingUpScreen(state: SettingUpState = SettingUpState(), onCancel: () -> Unit = {}) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Setting Up") })
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
                        CircularProgressIndicator(modifier = Modifier.size(48.dp))
                    }
                    is SettingUpVariant.RateLimited -> {
                        Icon(
                            imageVector = Icons.Default.HourglassEmpty,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = when (state.variant) {
                        is SettingUpVariant.FirstTime ->
                            "We're issuing a secure certificate for your device."
                        is SettingUpVariant.Refreshing ->
                            "Your certificate is being refreshed. " +
                                "This usually takes about 30 seconds."
                        is SettingUpVariant.RateLimited ->
                            "Certificate issuance is temporarily delayed."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                when (state.variant) {
                    is SettingUpVariant.FirstTime -> {
                        Text(
                            text = "This usually takes about 30 seconds.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is SettingUpVariant.Refreshing -> { /* no extra text */ }
                    is SettingUpVariant.RateLimited -> {
                        Text(
                            text = "Expected: ${state.variant.expectedDate}.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "We'll notify you when it's ready.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(32.dp))

                TextButton(onClick = onCancel) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun SettingUpFirstTimePreview() {
    RouseContextTheme(darkTheme = true) {
        SettingUpScreen(state = SettingUpState(SettingUpVariant.FirstTime))
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
