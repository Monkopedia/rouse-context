package com.rousecontext.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rousecontext.app.R
import com.rousecontext.app.ui.screens.AuditHistoryEntry

/**
 * Canonical tool-call row shared across dashboard, integration-manage, and
 * audit-history surfaces. Layout locked in the variant 7 review
 * (#343 / #363 / #365 / #366) and unified across surfaces in #370 so the
 * three "Recent Activity" areas all render the same widget.
 *
 * ```
 * [ toolName  ─────────────────  [pill] ]
 * [ 123 ms · {args...}  ─────── 10:32 AM ]
 * ```
 *
 * Row 1 is the tool name with the optional [ClientPill] right-aligned; row 2
 * is duration + args on the left and the call time on the right (with a
 * 4 dp end padding to visually balance the pill above it).
 *
 * When [AuditHistoryEntry.clientLabel] is null the pill is omitted entirely
 * — row 1 still renders, but without the trailing pill — per the #366
 * decision (rows predating schema v4 are rare, and a placeholder pill would
 * collide with real `Unknown (#N)` labels).
 *
 * [onClick] is nullable. When null the row renders as non-interactive
 * (the integration-manage and dashboard teaser surfaces pass a click
 * handler; some future read-only surface may choose not to).
 */
@Composable
fun ToolCallRow(entry: AuditHistoryEntry, onClick: (() -> Unit)? = null) {
    ListRow(onClick = onClick) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    entry.toolName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                if (entry.clientLabel != null) {
                    Spacer(modifier = Modifier.width(dimensionResource(R.dimen.spacing_sm)))
                    ClientPill(entry.clientLabel)
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            R.string.screen_audit_history_duration_ms,
                            entry.durationMs
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (entry.arguments.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(dimensionResource(R.dimen.spacing_md)))
                        Text(
                            entry.arguments,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.width(dimensionResource(R.dimen.spacing_sm)))
                Text(
                    entry.time,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = TIME_END_PADDING_DP.dp)
                )
            }
        }
    }
}

private const val TIME_END_PADDING_DP = 4
