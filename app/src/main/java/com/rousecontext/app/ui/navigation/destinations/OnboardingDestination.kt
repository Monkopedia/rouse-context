package com.rousecontext.app.ui.navigation.destinations

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.rousecontext.app.ui.navigation.ConfigureNavBar
import com.rousecontext.app.ui.navigation.Routes
import com.rousecontext.app.ui.screens.SettingUpContent
import com.rousecontext.app.ui.screens.SettingUpState
import com.rousecontext.app.ui.screens.SettingUpVariant
import com.rousecontext.app.ui.screens.WelcomeScreen
import com.rousecontext.app.ui.viewmodels.OnboardingState
import com.rousecontext.app.ui.viewmodels.OnboardingStep
import com.rousecontext.app.ui.viewmodels.OnboardingViewModel
import org.koin.androidx.compose.koinViewModel

fun NavGraphBuilder.onboardingDestination(navController: NavController) {
    composable(Routes.ONBOARDING) {
        ConfigureNavBar(
            title = "",
            showTopBar = false,
            showBottomBar = false
        )
        val onboardingViewModel: OnboardingViewModel =
            koinViewModel()
        val state by onboardingViewModel.state.collectAsState()

        LaunchedEffect(state) {
            // Only navigate to Home once the device is fully onboarded
            // (subdomain AND certs persisted, #389). In-progress and error
            // states stay on this screen so the user sees either progress
            // or a retryable failure.
            if (state is OnboardingState.Onboarded) {
                navController.navigate(Routes.HOME) {
                    popUpTo(Routes.ONBOARDING) {
                        inclusive = true
                    }
                    launchSingleTop = true
                }
            }
        }

        OnboardingBody(
            state = state,
            onGetStarted = {
                navController.navigate(
                    Routes.NOTIFICATION_PREFERENCES
                )
            },
            onRetry = { onboardingViewModel.retry() }
        )
    }
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
