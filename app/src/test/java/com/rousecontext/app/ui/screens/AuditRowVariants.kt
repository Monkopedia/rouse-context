package com.rousecontext.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rousecontext.app.R
import com.rousecontext.app.ui.components.ListDivider
import com.rousecontext.app.ui.components.ListRow
import com.rousecontext.app.ui.components.SectionHeader

/**
 * Sample audit-row data used by each variant. [clientLabel] is NOT part of
 * the production [AuditHistoryEntry] — it's faked here purely so the
 * screenshot test can render placement options for issue #343 before the
 * real `AuditEntry.clientLabel` field ships under #342 phase 1.
 */
internal data class VariantRowSample(
    val time: String,
    val toolName: String,
    val durationMs: Long,
    val arguments: String,
    val clientLabel: String
)

/**
 * A grouped view's sample: a session header showing the client once and the
 * rows posted under that session. Used by variants 5 and 6.
 */
internal data class VariantSessionSample(
    val clientLabel: String,
    val sessionTimeRange: String,
    val rows: List<VariantRowSample>
)

/**
 * Short, long, and unknown client labels rendered by every variant so the
 * reviewer sees how each placement handles the three length classes.
 */
internal object VariantSamples {
    val shortClient: VariantRowSample = VariantRowSample(
        time = "10:32 AM",
        toolName = "get_steps",
        durationMs = 142,
        arguments = "{days: 7}",
        clientLabel = "Claude"
    )

    val longClient: VariantRowSample = VariantRowSample(
        time = "10:31 AM",
        toolName = "list_active_notifications",
        durationMs = 89,
        arguments = "{limit: 50}",
        clientLabel = "Claude Desktop"
    )

    val unknownClient: VariantRowSample = VariantRowSample(
        time = "10:30 AM",
        toolName = "get_heart_rate",
        durationMs = 201,
        arguments = "{days: 1}",
        clientLabel = "Unknown (#1)"
    )

    val allRows: List<VariantRowSample> = listOf(shortClient, longClient, unknownClient)

    /**
     * A session-grouped sample containing two rows per session (short tool
     * + long tool). Variants 5 and 6 render the full list.
     */
    val sessions: List<VariantSessionSample> = listOf(
        VariantSessionSample(
            clientLabel = "Claude",
            sessionTimeRange = "10:32 \u2013 10:34 AM",
            rows = listOf(
                VariantRowSample(
                    time = "10:32 AM",
                    toolName = "get_steps",
                    durationMs = 142,
                    arguments = "{days: 7}",
                    clientLabel = "Claude"
                ),
                VariantRowSample(
                    time = "10:33 AM",
                    toolName = "list_active_notifications",
                    durationMs = 89,
                    arguments = "{limit: 50}",
                    clientLabel = "Claude"
                )
            )
        ),
        VariantSessionSample(
            clientLabel = "Claude Desktop",
            sessionTimeRange = "10:28 \u2013 10:29 AM",
            rows = listOf(
                VariantRowSample(
                    time = "10:29 AM",
                    toolName = "get_sleep",
                    durationMs = 108,
                    arguments = "{days: 1}",
                    clientLabel = "Claude Desktop"
                ),
                VariantRowSample(
                    time = "10:28 AM",
                    toolName = "get_heart_rate",
                    durationMs = 201,
                    arguments = "{days: 7}",
                    clientLabel = "Claude Desktop"
                )
            )
        ),
        VariantSessionSample(
            clientLabel = "Unknown (#1)",
            sessionTimeRange = "09:50 \u2013 09:51 AM",
            rows = listOf(
                VariantRowSample(
                    time = "09:51 AM",
                    toolName = "get_steps",
                    durationMs = 156,
                    arguments = "{days: 30}",
                    clientLabel = "Unknown (#1)"
                )
            )
        )
    )
}

/**
 * Common "tool name + time" header row used by every variant (except the
 * leading-initial-chip variant which shifts it right). Kept as a private
 * helper so each variant's layout differences are obvious at a glance.
 */
@Composable
private fun ToolNameAndTime(toolName: String, time: String, nameModifier: Modifier = Modifier) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            toolName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = nameModifier.weight(1f)
        )
        Text(
            time,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DurationAndArgs(durationMs: Long, arguments: String) {
    Row {
        Text(
            "${durationMs}ms",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(dimensionResource(R.dimen.spacing_md)))
        Text(
            arguments,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Deterministic color lookup for the leading-initial-chip variant. Each
 * client label maps to a stable hue; unknown labels fall back to a muted
 * tertiary. Purely cosmetic — the test only cares that each sample produces
 * a consistent color.
 */
private fun chipColorFor(clientLabel: String): Color = when {
    clientLabel.startsWith("Claude Desktop") -> Color(0xFF6B5BD6)
    clientLabel.startsWith("Claude") -> Color(0xFFC96442)
    else -> Color(0xFF6F7580)
}

// =============================================================================
// Variant 1 — Third row, full-width client label beneath duration+args.
// =============================================================================

@Composable
internal fun Variant1ThirdRow(sample: VariantRowSample) {
    ListRow {
        Column(modifier = Modifier.weight(1f)) {
            ToolNameAndTime(sample.toolName, sample.time)
            Spacer(modifier = Modifier.height(2.dp))
            DurationAndArgs(sample.durationMs, sample.arguments)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                sample.clientLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// =============================================================================
// Variant 2 — Client as prefix on the duration+args row.
// =============================================================================

@Composable
internal fun Variant2SecondRowPrefix(sample: VariantRowSample) {
    ListRow {
        Column(modifier = Modifier.weight(1f)) {
            ToolNameAndTime(sample.toolName, sample.time)
            Spacer(modifier = Modifier.height(2.dp))
            Row {
                Text(
                    sample.clientLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.width(dimensionResource(R.dimen.spacing_sm)))
                Text(
                    "\u00B7",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(dimensionResource(R.dimen.spacing_sm)))
                Text(
                    "${sample.durationMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(dimensionResource(R.dimen.spacing_sm)))
                Text(
                    "\u00B7",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(dimensionResource(R.dimen.spacing_sm)))
                Text(
                    sample.arguments,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// =============================================================================
// Variant 3 — Right-aligned pill next to the tool name.
// =============================================================================

@Composable
internal fun Variant3RightAlignedPill(sample: VariantRowSample) {
    ListRow {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    sample.toolName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(dimensionResource(R.dimen.spacing_sm)))
                ClientPill(sample.clientLabel)
                Spacer(modifier = Modifier.width(dimensionResource(R.dimen.spacing_sm)))
                Text(
                    sample.time,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            DurationAndArgs(sample.durationMs, sample.arguments)
        }
    }
}

@Composable
private fun ClientPill(clientLabel: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(10.dp)
            )
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            clientLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// =============================================================================
// Variant 4 — Leading colored-initial chip on the left side.
// =============================================================================

@Composable
internal fun Variant4LeadingInitial(sample: VariantRowSample) {
    ListRow {
        InitialChip(sample.clientLabel)
        Spacer(modifier = Modifier.width(dimensionResource(R.dimen.spacing_md)))
        Column(modifier = Modifier.weight(1f)) {
            ToolNameAndTime(sample.toolName, sample.time)
            Spacer(modifier = Modifier.height(2.dp))
            DurationAndArgs(sample.durationMs, sample.arguments)
        }
    }
}

@Composable
private fun InitialChip(clientLabel: String) {
    val initial = clientLabel.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '?'
    val bg = chipColorFor(clientLabel)
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            initial.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
    }
}

// =============================================================================
// Variant 5 — Session-grouped header; rows under it omit the client label.
// =============================================================================

@Composable
internal fun Variant5SessionGrouped(session: VariantSessionSample) {
    Column {
        SessionHeader(session)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                session.rows.forEachIndexed { index, row ->
                    PlainRow(row)
                    if (index < session.rows.lastIndex) ListDivider()
                }
            }
        }
    }
}

@Composable
private fun SessionHeader(session: VariantSessionSample) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = dimensionResource(R.dimen.spacing_lg),
                vertical = dimensionResource(R.dimen.spacing_sm)
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            session.clientLabel,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            session.sessionTimeRange,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PlainRow(sample: VariantRowSample) {
    ListRow {
        Column(modifier = Modifier.weight(1f)) {
            ToolNameAndTime(sample.toolName, sample.time)
            Spacer(modifier = Modifier.height(2.dp))
            DurationAndArgs(sample.durationMs, sample.arguments)
        }
    }
}

// =============================================================================
// Variant 6 — Variant 1 + Variant 5: row-level client label AND session header.
// =============================================================================

@Composable
internal fun Variant6RowLabelAndSessionHeader(session: VariantSessionSample) {
    Column {
        SessionHeader(session)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                session.rows.forEachIndexed { index, row ->
                    Variant1ThirdRow(row)
                    if (index < session.rows.lastIndex) ListDivider()
                }
            }
        }
    }
}

// =============================================================================
// Variant 7 — Right-aligned pill (row 1) with time stacked directly below.
// =============================================================================

@Composable
internal fun Variant7TimeBelowPill(sample: VariantRowSample) {
    ListRow {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    sample.toolName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(dimensionResource(R.dimen.spacing_sm)))
                ClientPill(sample.clientLabel)
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(modifier = Modifier.weight(1f)) {
                    Text(
                        "${sample.durationMs}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(dimensionResource(R.dimen.spacing_md)))
                    Text(
                        sample.arguments,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(dimensionResource(R.dimen.spacing_sm)))
                Text(
                    sample.time,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
        }
    }
}

// =============================================================================
// Card wrappers — each variant's screenshot renders three rows in one Card so
// the reviewer can compare short/long/unknown placements side by side.
// =============================================================================

@Composable
internal fun VariantRowsCard(
    rows: List<VariantRowSample>,
    rowContent: @Composable (VariantRowSample) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(dimensionResource(R.dimen.spacing_lg))
    ) {
        SectionHeader("Today")
        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_sm)))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                rows.forEachIndexed { index, row ->
                    rowContent(row)
                    if (index < rows.lastIndex) ListDivider()
                }
            }
        }
    }
}

@Composable
internal fun VariantSessionsCard(
    sessions: List<VariantSessionSample>,
    sessionContent: @Composable (VariantSessionSample) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(dimensionResource(R.dimen.spacing_lg))
    ) {
        SectionHeader("Today")
        sessions.forEachIndexed { index, session ->
            if (index > 0) Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_md)))
            sessionContent(session)
        }
    }
}
