package com.rousecontext.app.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.rousecontext.app.ui.theme.RouseContextTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose test for the Privacy row and the debug-only "Renew cert now" button
 * added in issue #381. Covers:
 *
 * 1. The Privacy row renders in the About section and fires its callback when tapped.
 * 2. The developer section only renders when `showDeveloperSection = true`.
 * 3. Tapping "Renew cert now" fires the `onRenewCertNow` callback and surfaces
 *    the "Renewal enqueued" confirmation text.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [33],
    qualifiers = "w400dp-h800dp-xxhdpi",
    application = com.rousecontext.app.TestApplication::class
)
class SettingsPrivacyRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun privacyRow_tapFiresCallback() {
        var privacyTaps = 0
        composeRule.setContent {
            RouseContextTheme(darkTheme = true) {
                SettingsContent(
                    showDeveloperSection = false,
                    onOpenPrivacy = { privacyTaps++ }
                )
            }
        }

        composeRule.onNodeWithText("Privacy")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        assertEquals(
            "Tapping the Privacy row must fire onOpenPrivacy exactly once",
            1,
            privacyTaps
        )
    }

    @Test
    fun developerSection_hiddenWhenFlagOff() {
        composeRule.setContent {
            RouseContextTheme(darkTheme = true) {
                SettingsContent(showDeveloperSection = false)
            }
        }

        // Scroll to the bottom so a section rendered after Support would be
        // visible if it existed.
        composeRule.onNodeWithText("Privacy").performScrollTo()

        val hasDeveloperHeader = runCatching {
            composeRule.onNodeWithText("Developer").assertIsDisplayed()
        }.isSuccess
        assertFalse(
            "Developer section must not render when showDeveloperSection = false",
            hasDeveloperHeader
        )
    }

    @Test
    fun renewCertButton_tapFiresCallbackAndShowsEnqueuedLabel() {
        var renewTaps = 0
        composeRule.setContent {
            RouseContextTheme(darkTheme = true) {
                SettingsContent(
                    showDeveloperSection = true,
                    onRenewCertNow = { renewTaps++ }
                )
            }
        }

        // "Renew cert now" is rendered twice: once as the row title label and
        // once as the TextButton's own label. Tap the TextButton (the second,
        // clickable match).
        val buttonMatches = composeRule.onAllNodesWithText("Renew cert now")
        buttonMatches[1].performScrollTo().performClick()

        assertTrue("Tapping Renew cert now must fire onRenewCertNow", renewTaps >= 1)
        composeRule.onNodeWithText("Renewal enqueued")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
