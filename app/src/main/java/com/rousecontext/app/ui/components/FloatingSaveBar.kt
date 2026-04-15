package com.rousecontext.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rousecontext.app.R

/**
 * A pinned Save button that animates in from the bottom when there are
 * unsaved changes on a settings screen.
 *
 * Usage: place this composable as a sibling to the settings content inside
 * a full-size container (e.g. `Box(Modifier.fillMaxSize()) { content; FloatingSaveBar(...) }`).
 * The bar positions itself at the bottom and respects the gesture-bar inset
 * via `navigationBarsPadding()`, matching the bottom-inset behaviour applied
 * to scaffolded content in #57.
 *
 * @param visible Whether the bar should be visible. Typically driven by a
 *   ViewModel `isDirty` state flow.
 * @param onSave Callback invoked when the Save button is tapped.
 * @param modifier Optional modifier applied to the outer container. Callers
 *   should use a Box-aligned modifier when embedding in a Box parent.
 * @param text Button label. Defaults to "Save".
 * @param enabled Whether the Save button can be tapped while visible.
 */
@Composable
fun FloatingSaveBar(
    visible: Boolean,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    text: String = stringResource(R.string.common_save),
    enabled: Boolean = true
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = onSave,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(text)
            }
        }
    }
}

/**
 * Convenience container that overlays a [FloatingSaveBar] over [content].
 * The content occupies the full parent and the save bar pins to the bottom.
 *
 * Use this when the settings screen content is a simple Column that you
 * don't need to reserve bottom padding for; the floating bar overlays the
 * tail of the scroll region (which, per the Material guidance for this
 * pattern, is acceptable because the bar is only shown while the user is
 * in the middle of an edit).
 */
@Composable
fun FloatingSaveBarOverlay(
    visible: Boolean,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    text: String = stringResource(R.string.common_save),
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        content()
        FloatingSaveBar(
            visible = visible,
            onSave = onSave,
            text = text,
            enabled = enabled,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
