package com.rousecontext.app.ui.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.rousecontext.app.ui.components.ListDivider
import com.rousecontext.app.ui.components.SwitchRow
import com.rousecontext.app.ui.theme.RouseContextTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Locks the shared [SwitchRow] appearance across the four
 * on/off x enabled/disabled states in both light and dark theme (#484).
 *
 * The off (unchecked) state used to fall back to the Material3 default
 * unchecked palette, which is low-contrast in the navy+amber theme and read as
 * disabled. These goldens pin the explicit `SwitchDefaults.colors(...)` so an
 * off-but-enabled switch stays visually distinct from a disabled one.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [33],
    qualifiers = "w400dp-h400dp-xxhdpi",
    application = com.rousecontext.app.TestApplication::class
)
class SwitchRowScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Composable
    private fun AllStates() {
        Column(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
            SwitchRow(
                title = "On, enabled",
                subtitle = "checked = true, enabled = true",
                checked = true,
                onCheckedChange = {},
                enabled = true
            )
            ListDivider()
            SwitchRow(
                title = "Off, enabled",
                subtitle = "checked = false, enabled = true",
                checked = false,
                onCheckedChange = {},
                enabled = true
            )
            ListDivider()
            SwitchRow(
                title = "On, disabled",
                subtitle = "checked = true, enabled = false",
                checked = true,
                onCheckedChange = {},
                enabled = false
            )
            ListDivider()
            SwitchRow(
                title = "Off, disabled",
                subtitle = "checked = false, enabled = false",
                checked = false,
                onCheckedChange = {},
                enabled = false
            )
        }
    }

    @Test
    fun switchRowStatesDark() {
        composeRule.setContent {
            RouseContextTheme(darkTheme = true) {
                Column(modifier = Modifier.fillMaxWidth()) { AllStates() }
            }
        }
        composeRule.onRoot().captureRoboImage("screenshots/60_switch_row_states_dark.png")
    }

    @Test
    fun switchRowStatesLight() {
        composeRule.setContent {
            RouseContextTheme(darkTheme = false) {
                Column(modifier = Modifier.fillMaxWidth()) { AllStates() }
            }
        }
        composeRule.onRoot().captureRoboImage("screenshots/60_switch_row_states_light.png")
    }
}
