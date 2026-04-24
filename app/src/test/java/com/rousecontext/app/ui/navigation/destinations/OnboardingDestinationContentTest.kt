package com.rousecontext.app.ui.navigation.destinations

import androidx.compose.ui.test.junit4.createComposeRule
import com.rousecontext.app.ui.theme.RouseContextTheme
import com.rousecontext.app.ui.viewmodels.OnboardingState
import com.rousecontext.app.ui.viewmodels.OnboardingStep
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Regression tests for #392.
 *
 * Prior to this fix NotificationPreferences called `startOnboarding()` on
 * its own NavBackStackEntry-scoped [OnboardingViewModel] instance and then
 * navigated to the onboarding destination, which created a *different*
 * ViewModel that re-read the CertificateStore's subdomain (still null at
 * that instant) and was stuck on Welcome forever. The in-flight
 * registration did complete on the discarded VM, but nothing observed it.
 *
 * The fix scopes the `startOnboarding()` trigger to the onboarding
 * destination itself via an `autostart=true` nav arg. These tests drive
 * the extracted [OnboardingDestinationContent] composable to prove that:
 *
 *  1. When `autostart=true`, the destination's own `onStartOnboarding`
 *     callback fires exactly once on entry.
 *  2. When `autostart=false`, it does NOT fire (returning-Welcome path).
 *  3. When the VM transitions to [OnboardingState.Onboarded],
 *     `onOnboarded` fires so the destination can navigate to Home.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [33],
    qualifiers = "w400dp-h800dp-xxhdpi",
    application = com.rousecontext.app.TestApplication::class
)
class OnboardingDestinationContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun autostartTrue_firesStartOnboardingOnce() {
        val stateFlow = MutableStateFlow<OnboardingState>(OnboardingState.NotOnboarded)
        var startCount = 0

        composeRule.setContent {
            RouseContextTheme(darkTheme = true) {
                OnboardingDestinationContent(
                    autostart = true,
                    stateFlow = stateFlow,
                    onStartOnboarding = { startCount += 1 },
                    onRetry = {},
                    onGetStarted = {},
                    onOnboarded = {}
                )
            }
        }

        composeRule.waitForIdle()
        assertEquals(
            "autostart should invoke onStartOnboarding exactly once",
            1,
            startCount
        )
    }

    @Test
    fun autostartFalse_doesNotFireStartOnboarding() {
        val stateFlow = MutableStateFlow<OnboardingState>(OnboardingState.NotOnboarded)
        var started = false

        composeRule.setContent {
            RouseContextTheme(darkTheme = true) {
                OnboardingDestinationContent(
                    autostart = false,
                    stateFlow = stateFlow,
                    onStartOnboarding = { started = true },
                    onRetry = {},
                    onGetStarted = {},
                    onOnboarded = {}
                )
            }
        }

        composeRule.waitForIdle()
        assertFalse(
            "onStartOnboarding must not fire on plain Welcome entry",
            started
        )
    }

    @Test
    fun stateOnboarded_firesOnOnboardedCallback() {
        val stateFlow = MutableStateFlow<OnboardingState>(OnboardingState.NotOnboarded)
        var onboardedFired = false

        composeRule.setContent {
            RouseContextTheme(darkTheme = true) {
                OnboardingDestinationContent(
                    autostart = false,
                    stateFlow = stateFlow,
                    onStartOnboarding = {},
                    onRetry = {},
                    onGetStarted = {},
                    onOnboarded = { onboardedFired = true }
                )
            }
        }

        composeRule.waitForIdle()
        assertFalse(
            "onOnboarded must not fire while state is NotOnboarded",
            onboardedFired
        )

        // The #392 regression scenario: the same VM transitions to
        // Onboarded after background registration completes. The
        // destination must observe that transition and navigate.
        stateFlow.value = OnboardingState.Onboarded
        composeRule.waitForIdle()

        assertTrue(
            "onOnboarded must fire once state becomes Onboarded",
            onboardedFired
        )
    }

    @Test
    fun autostart_fullFlowTransitionsToOnboarded() {
        // End-to-end: autostart triggers start, state progresses through
        // InProgress variants, and Onboarded fires the navigation.
        val stateFlow = MutableStateFlow<OnboardingState>(OnboardingState.NotOnboarded)
        var startCount = 0
        var onboardedFired = false

        composeRule.setContent {
            RouseContextTheme(darkTheme = true) {
                OnboardingDestinationContent(
                    autostart = true,
                    stateFlow = stateFlow,
                    onStartOnboarding = {
                        startCount += 1
                        // Mirror the real VM: startOnboarding flips to
                        // InProgress, then eventually to Onboarded. We
                        // drive that via the StateFlow here so the test
                        // composition actually re-renders.
                        stateFlow.value = OnboardingState.InProgress(
                            OnboardingStep.Registering
                        )
                    },
                    onRetry = {},
                    onGetStarted = {},
                    onOnboarded = { onboardedFired = true }
                )
            }
        }

        composeRule.waitForIdle()
        assertEquals(1, startCount)

        // Simulate the appScope-launched registration completing.
        stateFlow.value = OnboardingState.InProgress(
            OnboardingStep.ProvisioningCerts
        )
        composeRule.waitForIdle()
        assertFalse("not yet Onboarded", onboardedFired)

        stateFlow.value = OnboardingState.Onboarded
        composeRule.waitForIdle()
        assertTrue(
            "destination must navigate to Home once Onboarded fires",
            onboardedFired
        )
        // Recomposing under Onboarded must NOT re-trigger startOnboarding.
        assertEquals(
            "autostart must not re-fire on state changes",
            1,
            startCount
        )
    }
}
