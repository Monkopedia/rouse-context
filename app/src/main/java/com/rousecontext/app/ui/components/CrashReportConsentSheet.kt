package com.rousecontext.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.rousecontext.app.R
import com.rousecontext.app.ui.theme.RouseContextTheme

/**
 * First-run crash-reporting consent, as a modal bottom sheet over the
 * dashboard (issue #546).
 *
 * Dismissible by design — it must not block first run. Every exit route
 * (Turn on, Not now, swipe, back) is a final answer: the caller records that
 * the question was asked and never shows the sheet again. Dismissing leaves
 * reporting off, and Settings › Support is the only other route in.
 *
 * @param onTurnOn user opted in; enable and persist collection.
 * @param onDismiss every other exit; leave collection off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashReportConsentSheet(onTurnOn: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        CrashReportConsentContent(onTurnOn = onTurnOn, onDismiss = onDismiss)
    }
}

/**
 * The sheet's body, separated from [ModalBottomSheet] so it can be rendered
 * (and screenshot-pinned) without a dialog window.
 */
@Composable
fun CrashReportConsentContent(
    onTurnOn: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var detailsExpanded by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(
                start = dimensionResource(R.dimen.spacing_lg),
                end = dimensionResource(R.dimen.spacing_lg),
                bottom = dimensionResource(R.dimen.spacing_lg)
            )
    ) {
        Text(
            text = stringResource(R.string.crash_consent_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_md)))
        Text(
            text = stringResource(R.string.crash_consent_body),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_lg)))
        PrivacyWarningCard(text = stringResource(R.string.crash_consent_public_warning))
        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_lg)))
        Text(
            text = stringResource(R.string.crash_consent_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_md)))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { detailsExpanded = !detailsExpanded }
                .padding(vertical = dimensionResource(R.dimen.spacing_sm)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.crash_consent_details_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (detailsExpanded) {
                    Icons.Default.ExpandLess
                } else {
                    Icons.Default.ExpandMore
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
        if (detailsExpanded) {
            Text(
                text = stringResource(R.string.crash_consent_details_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_sm)))
        }
        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_lg)))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(
                dimensionResource(R.dimen.spacing_md)
            )
        ) {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.crash_consent_not_now))
            }
            Button(
                onClick = onTurnOn,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.crash_consent_turn_on))
            }
        }
        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_md)))
        Text(
            text = stringResource(R.string.crash_consent_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Preview(showBackground = true)
@Composable
fun CrashReportConsentContentPreview() {
    RouseContextTheme {
        CrashReportConsentContent(onTurnOn = {}, onDismiss = {})
    }
}
