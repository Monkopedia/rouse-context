package com.rousecontext.app.ui.navigation.destinations

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.rousecontext.app.ui.navigation.ConfigureNavBar
import com.rousecontext.app.ui.navigation.Routes
import com.rousecontext.app.ui.screens.SettingUpContent
import com.rousecontext.app.ui.screens.SettingUpState
import com.rousecontext.app.ui.screens.SettingUpVariant
import com.rousecontext.app.ui.screens.WelcomeScreen
import com.rousecontext.app.ui.viewmodels.OnboardingState
import com.rousecontext.app.ui.viewmodels.OnboardingStep
import com.rousecontext.app.ui.viewmodels.OnboardingViewModel
import kotlinx.coroutines.flow.StateFlow
import org.koin.androidx.compose.koinViewModel

fun NavGraphBuilder.onboardingDestination(navController: NavController) {
    composable(
        route = Routes.ONBOARDING,
        arguments = listOf(
            navArgument(Routes.AUTOSTART_ARG) {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }
        )
    ) { backStackEntry ->
        ConfigureNavBar(
            title = "",
            showTopBar = false,
            showBottomBar = false
        )
        val onboardingViewModel: OnboardingViewModel =
            koinViewModel()
        // #392: Fresh-install onboarding used to fail because
        // NotificationPreferences called startOnboarding() on its own
        // VM (scoped to its own NavBackStackEntry), then navigated here
        // which created a brand-new VM that never observed the in-flight
        // flow. With [Routes.onboardingAutostart] the Continue button
        // instead navigates us back with autostart=true and THIS
        // destination's VM drives the flow — so the same VM that kicks
        // off registration is also the one observing it finish and
        // navigating to Home.
        val autostart = backStackEntry.arguments
            ?.getString(Routes.AUTOSTART_ARG) == "true"

        OnboardingDestinationContent(
            autostart = autostart,
            stateFlow = onboardingViewModel.state,
            onStartOnboarding = { onboardingViewModel.startOnboarding() },
            onRetry = { onboardingViewModel.retry() },
            onGetStarted = {
                navController.navigate(Routes.NOTIFICATION_PREFERENCES)
            },
            onOnboarded = {
                navController.navigate(Routes.HOME) {
                    popUpTo(Routes.ONBOARDING) {
                        inclusive = true
                    }
                    launchSingleTop = true
                }
            }
        )
    }
}

/**
 * Pure composable body of the onboarding destination, separated from the
 * NavGraph/ViewModel plumbing so it can be driven directly from Robolectric
 * Compose tests. The [onStartOnboarding] callback is fired exactly once per
 * destination entry when [autostart] is true — this is the invariant broken
 * by #392's two-VM split and restored by scoping the autostart trigger to
 * this destination instead of NotificationPreferences.
 */
@Composable
internal fun OnboardingDestinationContent(
    autostart: Boolean,
    stateFlow: StateFlow<OnboardingState>,
    onStartOnboarding: () -> Unit,
    onRetry: () -> Unit,
    onGetStarted: () -> Unit,
    onOnboarded: () -> Unit
) {
    val state by stateFlow.collectAsState()

    LaunchedEffect(autostart) {
        if (autostart) {
            onStartOnboarding()
        }
    }

    LaunchedEffect(state) {
        // Only navigate to Home once the device is fully onboarded
        // (subdomain AND certs persisted, #389). In-progress and error
        // states stay on this screen so the user sees either progress
        // or a retryable failure.
        if (state is OnboardingState.Onboarded) {
            onOnboarded()
        }
    }

    OnboardingBody(
        state = state,
        onGetStarted = onGetStarted,
        onRetry = onRetry
    )
}

@Composable
private fun OnboardingBody(state: OnboardingState, onGetStarted: () -> Unit, onRetry: () -> Unit) {
    when (state) {
        is OnboardingState.Checking,
        is OnboardingState.NotOnboarded -> {
            WelcomeScreen(onGetStarted = onGetStarted)
        }
        is OnboardingState.InProgress -> {
            SettingUpContent(
                state = SettingUpState(
                    variant = when (state.step) {
                        OnboardingStep.Registering -> SettingUpVariant.Registering
                        OnboardingStep.ProvisioningCerts -> SettingUpVariant.Requesting
                    }
                ),
                onCancel = { /* no-op: block back out of in-flight onboarding */ }
            )
        }
        is OnboardingState.Failed -> {
            SettingUpContent(
                state = SettingUpState(
                    variant = SettingUpVariant.Failed(state.message)
                ),
                onRetry = onRetry,
                onCancel = { /* stay on screen; retry is the only forward action */ }
            )
        }
        is OnboardingState.RateLimited -> {
            SettingUpContent(
                state = SettingUpState(
                    variant = SettingUpVariant.RateLimited(state.retryDate)
                ),
                onCancel = { /* stay on screen; user must wait for quota */ }
            )
        }
        is OnboardingState.Onboarded -> {
            // LaunchedEffect above navigates to Home immediately.
            // Show nothing during the one-frame crossfade.
        }
    }
}
