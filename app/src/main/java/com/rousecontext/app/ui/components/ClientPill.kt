package com.rousecontext.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Small rounded-corner pill that labels an MCP client on audit rows.
 *
 * Visual language locked in during the variant 7 review (#343 / #363 / #365):
 * 10 dp rounded corners, outline-variant stroke, surface-variant fill, and
 * [labelSmall][androidx.compose.material3.Typography.labelSmall] text in
 * `onSurfaceVariant`.
 *
 * Callers pass the already-resolved `clientLabel` string (e.g. `"Claude"`,
 * `"Claude Desktop"`, or `"Unknown (#1)"` from the [UnknownClientLabeler]).
 * For rows whose `clientLabel` is null the caller must skip rendering the
 * pill entirely rather than pass a placeholder — see [ToolCallRow] for the
 * decision rationale.
 */
@Composable
fun ClientPill(clientLabel: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(PILL_CORNER_RADIUS_DP.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(PILL_CORNER_RADIUS_DP.dp)
            )
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(
                horizontal = PILL_HORIZONTAL_PADDING_DP.dp,
                vertical = PILL_VERTICAL_PADDING_DP.dp
            )
    ) {
        Text(
            clientLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private const val PILL_CORNER_RADIUS_DP = 10
private const val PILL_HORIZONTAL_PADDING_DP = 8
private const val PILL_VERTICAL_PADDING_DP = 2
