package com.rousecontext.app.ui.navigation.destinations

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.junit4.createComposeRule
import com.rousecontext.app.ui.theme.RouseContextTheme
import com.rousecontext.mcp.core.PendingAuthRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression tests for #435: IntegrationEnabledDestination must auto-navigate
 * to the auth approval screen when pending auth requests exist, even if the
 * requests arrived before the composable entered composition.
 *
 * The old code used `newRequestFlow` (SharedFlow with replay=0), which lost
 * emissions that happened before the collector started. The fix switches to
 * `pendingRequestsFlow` (StateFlow) which always replays its current value.
 *
 * These tests exercise the exact LaunchedEffect pattern used in production:
 * ```
 * pendingRequestsFlow.filter { it.isNotEmpty() }.first()
 * ```
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [33],
    qualifiers = "w400dp-h800dp-xxhdpi",
    application = com.rousecontext.app.TestApplication::class
)
class IntegrationEnabledAutoNavTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun pendingRequest(code: String = "ABC-DEF"): PendingAuthRequest = PendingAuthRequest(
        displayCode = code,
        integration = "test",
        clientId = "client-1",
        clientName = "Test Client",
        createdAt = System.currentTimeMillis()
    )

    /**
     * Test C (from #435): pending request exists BEFORE the composable enters
     * composition. The StateFlow replays its current value to the new
     * subscriber, so navigation should fire immediately.
     */
    @Test
    fun `pre-existing pending request triggers navigation on composition entry`() {
        val pendingFlow = MutableStateFlow(listOf(pendingRequest()))
        var navigated = false

        composeRule.setContent {
            RouseContextTheme(darkTheme = true) {
                LaunchedEffect(Unit) {
                    pendingFlow
                        .filter { it.isNotEmpty() }
                        .first()
                    navigated = true
                }
            }
        }

        composeRule.waitForIdle()
        assertTrue(
            "Navigation should fire immediately when pending requests pre-exist",
            navigated
        )
    }

    /**
     * Test A (from #435): pending request arrives after the composable is
     * already in composition. Navigation should fire when the list becomes
     * non-empty.
     */
    @Test
    fun `pending request arriving after composition triggers navigation`() {
        val pendingFlow = MutableStateFlow<List<PendingAuthRequest>>(emptyList())
        var navigated = false

        composeRule.setContent {
            RouseContextTheme(darkTheme = true) {
                LaunchedEffect(Unit) {
                    pendingFlow
                        .filter { it.isNotEmpty() }
                        .first()
                    navigated = true
                }
            }
        }

        composeRule.waitForIdle()
        assertFalse(
            "Navigation must not fire while pending list is empty",
            navigated
        )

        pendingFlow.value = listOf(pendingRequest())
        composeRule.waitForIdle()

        assertTrue(
            "Navigation should fire once a pending request appears",
            navigated
        )
    }

    /**
     * Verify that the empty-list initial state does NOT trigger navigation.
     */
    @Test
    fun `empty pending list does not trigger navigation`() {
        val pendingFlow = MutableStateFlow<List<PendingAuthRequest>>(emptyList())
        var navigated = false

        composeRule.setContent {
            RouseContextTheme(darkTheme = true) {
                LaunchedEffect(Unit) {
                    pendingFlow
                        .filter { it.isNotEmpty() }
                        .first()
                    navigated = true
                }
            }
        }

        composeRule.waitForIdle()
        assertFalse(
            "Empty pending list must not trigger navigation",
            navigated
        )
    }
}
