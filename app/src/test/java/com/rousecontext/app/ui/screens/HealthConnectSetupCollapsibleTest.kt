package com.rousecontext.app.ui.screens

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.rousecontext.app.ui.theme.RouseContextTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose test for the per-category collapsibility of the Health Connect
 * record-type list in [HealthConnectSetupContent].
 *
 * Verifies:
 * 1. All sections are collapsed by default — no record type display names
 *    should be visible on initial render.
 * 2. Clicking a category header reveals the record types in that category.
 * 3. Clicking the same header again collapses them.
 *
 * See GitHub issue #60.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [33],
    qualifiers = "w400dp-h800dp-xxhdpi",
    application = com.rousecontext.app.TestApplication::class
)
class HealthConnectSetupCollapsibleTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun recordTypeList_defaultsToAllCollapsed() {
        composeRule.setContent {
            RouseContextTheme(darkTheme = true) {
                HealthConnectSetupContent()
            }
        }

        // Category headers should be visible (they are always shown).
        composeRule.onNodeWithText("Activity").assertIsDisplayed()

        // But none of the record-type display names should be rendered
        // when sections are collapsed. Pick names that do not collide with
        // the always-visible category header labels (e.g. "Sleep" is also a
        // category name, so use "Exercise", "Heart Rate", etc.).
        composeRule.onNodeWithText("Steps").assertDoesNotExistOrNotDisplayed()
        composeRule.onNodeWithText("Heart Rate").assertDoesNotExistOrNotDisplayed()
        composeRule.onNodeWithText("Exercise").assertDoesNotExistOrNotDisplayed()
        composeRule.onNodeWithText("Weight").assertDoesNotExistOrNotDisplayed()
        composeRule.onNodeWithText("Hydration").assertDoesNotExistOrNotDisplayed()
    }

    @Test
    fun clickingCategoryHeader_expandsAndRevealsRecordTypes() {
        composeRule.setContent {
            RouseContextTheme(darkTheme = true) {
                HealthConnectSetupContent()
            }
        }

        // Collapsed by default.
        composeRule.onNodeWithText("Steps").assertDoesNotExistOrNotDisplayed()

        // Expand the Activity section.
        composeRule.onNodeWithText("Activity").performScrollTo().performClick()
        composeRule.waitForIdle()

        // Now the Activity record types should be visible.
        composeRule.onNodeWithText("Steps").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Exercise").performScrollTo().assertIsDisplayed()

        // Other categories remain collapsed.
        composeRule.onNodeWithText("Heart Rate").assertDoesNotExistOrNotDisplayed()
    }

    @Test
    fun clickingExpandedCategory_collapsesItAgain() {
        composeRule.setContent {
            RouseContextTheme(darkTheme = true) {
                HealthConnectSetupContent()
            }
        }

        // Expand.
        composeRule.onNodeWithText("Activity").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Steps").performScrollTo().assertIsDisplayed()

        // Collapse.
        composeRule.onNodeWithText("Activity").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Steps").assertDoesNotExistOrNotDisplayed()
    }

    // Helper: accepts either "not found in tree" or "present but not displayed"
    // as evidence that the node is hidden. AnimatedVisibility may fully remove
    // nodes when collapsed, depending on the enter/exit transition.
    private fun SemanticsNodeInteraction.assertDoesNotExistOrNotDisplayed() {
        val notDisplayed = runCatching { assertIsNotDisplayed() }.isSuccess
        val doesNotExist = runCatching { assertDoesNotExist() }.isSuccess
        check(notDisplayed || doesNotExist) {
            "Expected node to be hidden (not displayed or not in tree)"
        }
    }
}
