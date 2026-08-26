package com.rousecontext.app.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.rousecontext.app.ui.theme.RouseContextTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The primary button on the Health Connect setup screen is state-aware (#537).
 *
 * Before #537 the label branched on [SetupMode] alone, so in SETUP mode it
 * read "Grant All Health Access" even after the base permission had been
 * granted — which never showed up in practice because granting immediately
 * navigated away. Now that the screen stays put after the grant (so the
 * historical-access button is reachable), the button has to turn into an
 * explicit "Continue" that advances the flow.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [33],
    qualifiers = "w400dp-h800dp-xxhdpi",
    application = com.rousecontext.app.TestApplication::class
)
class HealthConnectSetupPrimaryButtonTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var grantClicks = 0
    private var continueClicks = 0

    private fun setContent(mode: SetupMode, grantedRecordTypes: Set<String>) {
        composeRule.setContent {
            RouseContextTheme(darkTheme = true) {
                HealthConnectSetupContent(
                    mode = mode,
                    onGrantAccess = { grantClicks++ },
                    onContinue = { continueClicks++ },
                    grantedRecordTypes = grantedRecordTypes
                )
            }
        }
    }

    @Test
    fun `setup mode with nothing granted asks for the grant`() {
        setContent(SetupMode.SETUP, emptySet())

        composeRule.onNodeWithText("Grant All Health Access")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        assertEquals(1, grantClicks)
        assertEquals(0, continueClicks)
    }

    @Test
    fun `setup mode after the base grant offers continue`() {
        setContent(SetupMode.SETUP, setOf("Steps"))

        composeRule.onNodeWithText("Grant All Health Access").assertDoesNotExist()
        composeRule.onNodeWithText("Continue")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        assertEquals(1, continueClicks)
        assertEquals(0, grantClicks)
    }

    @Test
    fun `settings mode keeps the manage action regardless of grants`() {
        setContent(SetupMode.SETTINGS, setOf("Steps"))

        composeRule.onNodeWithText("Continue").assertDoesNotExist()
        composeRule.onNodeWithText("Manage in Health Connect")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        assertEquals(1, grantClicks)
        assertEquals(0, continueClicks)
    }
}
