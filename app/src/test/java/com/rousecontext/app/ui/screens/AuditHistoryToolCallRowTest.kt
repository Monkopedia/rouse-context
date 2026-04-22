package com.rousecontext.app.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.rousecontext.app.ui.theme.RouseContextTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Verifies the variant-7 [ToolCallRow] layout (#366): the client pill is
 * rendered iff the bound [AuditHistoryEntry.clientLabel] is non-null, the
 * tool name and time always render, and the row still composes cleanly when
 * the label is absent (rows predating the schema v3 -> v4 migration from
 * #344).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [33],
    qualifiers = "w400dp-h800dp-xxhdpi",
    application = com.rousecontext.app.TestApplication::class
)
class AuditHistoryToolCallRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun populatedClientLabel_rendersPill() {
        composeRule.setContent {
            RouseContextTheme(darkTheme = true) {
                AuditHistoryScreen(
                    state = AuditHistoryState(
                        groups = listOf(
                            AuditHistoryGroup.ofEntries(
                                "Today",
                                listOf(
                                    AuditHistoryEntry(
                                        time = "10:32 AM",
                                        toolName = "health/get_steps",
                                        durationMs = 142,
                                        arguments = "{days: 7}",
                                        clientLabel = "Claude Desktop"
                                    )
                                )
                            )
                        )
                    )
                )
            }
        }

        composeRule.onNodeWithText("health/get_steps").assertIsDisplayed()
        composeRule.onNodeWithText("Claude Desktop").assertIsDisplayed()
        composeRule.onNodeWithText("10:32 AM").assertIsDisplayed()
    }

    @Test
    fun nullClientLabel_omitsPillButRowStillRenders() {
        composeRule.setContent {
            RouseContextTheme(darkTheme = true) {
                AuditHistoryScreen(
                    state = AuditHistoryState(
                        groups = listOf(
                            AuditHistoryGroup.ofEntries(
                                "Today",
                                listOf(
                                    AuditHistoryEntry(
                                        time = "9:45 AM",
                                        toolName = "health/get_sleep",
                                        durationMs = 89,
                                        arguments = "{days: 1}",
                                        clientLabel = null
                                    )
                                )
                            )
                        )
                    )
                )
            }
        }

        // The row itself still renders.
        composeRule.onNodeWithText("health/get_sleep").assertIsDisplayed()
        composeRule.onNodeWithText("9:45 AM").assertIsDisplayed()
        // Unknown (#1) is the placeholder that the UnknownClientLabeler would
        // produce; assert it is NOT rendered — this row has no label at all.
        assert(
            composeRule.onAllNodesWithText("Unknown (#1)")
                .fetchSemanticsNodes().isEmpty()
        ) { "ClientPill should be omitted when clientLabel is null" }
    }
}
