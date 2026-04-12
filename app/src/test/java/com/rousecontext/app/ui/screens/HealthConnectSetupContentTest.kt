package com.rousecontext.app.ui.screens

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.rousecontext.app.ui.theme.RouseContextTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose/Robolectric test that verifies the visual wiring between
 * `HealthConnectSetupViewModel.historicalAccessGranted` (a `StateFlow<Boolean>`)
 * and the `HistoricalAccessSection` composable inside
 * [HealthConnectSetupContent].
 *
 * Covers three transitions on a single composition so the swap is exercised
 * end-to-end:
 *
 * 1. Initial ungranted -> "Allow historical data" card visible.
 * 2. Flow flips to true -> "Historical access enabled" card visible.
 * 3. Flow flips back to false -> "Allow historical data" card re-appears.
 *
 * See GitHub issue #71.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [33],
    qualifiers = "w400dp-h800dp-xxhdpi",
    application = com.rousecontext.app.TestApplication::class
)
class HealthConnectSetupContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun historicalAccessSection_swapsWithStateFlow() {
        val flow = MutableStateFlow(false)

        composeRule.setContent {
            RouseContextTheme(darkTheme = true) {
                val granted by flow.collectAsState()
                HealthConnectSetupContent(historicalAccessGranted = granted)
            }
        }

        // (1) Initial ungranted state: the "Allow historical data" prompt
        // should be present with the Grant button. The section lives below
        // the record-type list, so scroll to it before asserting it is
        // actually on screen.
        composeRule.onNodeWithText("Allow historical data")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Grant historical access")
            .performScrollTo()
            .assertIsDisplayed()

        // (2) Flip to granted: the confirmation card should replace the
        // prompt.
        flow.value = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Historical access enabled")
            .performScrollTo()
            .assertIsDisplayed()

        // (3) Flip back to ungranted: the prompt should re-appear.
        flow.value = false
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Allow historical data")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Grant historical access")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
