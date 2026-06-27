package com.rousecontext.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.rousecontext.app.delivery.DistributorOption
import com.rousecontext.app.ui.components.appBarColors
import com.rousecontext.app.ui.theme.SuccessGreen
import com.rousecontext.app.ui.viewmodels.DistributorNudge

/**
 * The "Background delivery" picker (issue #463, foss flavor). Lists the
 * installed UnifiedPush distributors as one-tap rows; offers ntfy + an
 * "install another app" deep-link when nothing is installed. Renders the
 * design approved from `FossDeliveryMockTest`.
 *
 * In onboarding ([settingsMode] = false) it offers "Skip for now" (degrade,
 * don't block). Reached from Settings ([settingsMode] = true) it shows a back
 * arrow and marks the active distributor.
 *
 * [contextNote], when non-null, renders a contextual strip above the picker.
 * #474 uses it when the user was redirected here mid "Add integration" on a
 * not-yet-registered foss device ("Set up a delivery app to finish adding
 * integrations.").
 *
 * Pure/state-driven so it renders in Roborazzi without UnifiedPush or Koin.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundDeliveryScreen(
    rows: List<DistributorOption>,
    settingsMode: Boolean,
    onSelect: (DistributorOption) -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit,
    contextNote: String? = null,
    nudge: DistributorNudge? = null,
    onOpenDistributor: (DistributorNudge) -> Unit = {},
    onDismissNudge: () -> Unit = {}
) {
    Scaffold(
        // This screen is an ONBOARDING_ROUTES destination, so the persistent
        // root AppTopBar is suppressed and we draw our own bar. The root
        // Scaffold already consumes the system-bar insets (contentWindowInsets =
        // safeDrawing), so this nested Scaffold/TopAppBar must NOT re-apply them
        // or the status-bar inset is counted twice — an empty band above the
        // title and extra bottom padding. See #526.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Background delivery") },
                colors = appBarColors(),
                windowInsets = WindowInsets(0, 0, 0, 0),
                navigationIcon = {
                    if (settingsMode) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
        ) {
            Spacer(Modifier.height(20.dp))
            if (contextNote != null) {
                ContextNoteStrip(contextNote)
                Spacer(Modifier.height(16.dp))
            }
            Text(
                text = "Pick an app to wake your phone when an AI client connects.",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(20.dp))

            rows.forEach { row ->
                DistributorRow(row, onClick = { onSelect(row) })
                Spacer(Modifier.height(12.dp))
            }

            if (nudge != null) {
                DistributorNudgeCard(
                    nudge = nudge,
                    onOpen = { onOpenDistributor(nudge) },
                    onDismiss = onDismissNudge
                )
                Spacer(Modifier.height(12.dp))
            }

            if (!settingsMode) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                    Text("Skip for now")
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/**
 * The freshly-installed-distributor nudge (issue #480). Shown after the user
 * picks a distributor that hasn't reported an endpoint within the wait window —
 * the Android "stopped state" case. The action launches the distributor (which
 * clears the stopped state so its registration fires); the nudge then clears
 * automatically once the endpoint arrives.
 */
@Composable
private fun DistributorNudgeCard(
    nudge: DistributorNudge,
    onOpen: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = "We haven't heard from ${nudge.distributorName} yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss"
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Text("Open ${nudge.distributorName} to enable")
            }
        }
    }
}

@Composable
private fun ContextNoteStrip(note: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = note,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DistributorRow(row: DistributorOption, onClick: () -> Unit) {
    val isInstall = row.kind == DistributorOption.Kind.INSTALL_NTFY ||
        row.kind == DistributorOption.Kind.INSTALL_OTHER
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isInstall) Icons.Default.Add else Icons.Default.Notifications,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(row.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = row.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.kind == DistributorOption.Kind.ACTIVE) {
                        SuccessGreen
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            TrailingIcon(row.kind)
        }
    }
}

@Composable
private fun TrailingIcon(kind: DistributorOption.Kind) {
    val (icon: ImageVector, tint: Color) = when (kind) {
        DistributorOption.Kind.ACTIVE -> Icons.Default.CheckCircle to SuccessGreen
        else ->
            Icons.AutoMirrored.Filled.ArrowForward to
                MaterialTheme.colorScheme.onSurfaceVariant
    }
    Icon(imageVector = icon, contentDescription = null, tint = tint)
}
