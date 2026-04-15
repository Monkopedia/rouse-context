package com.rousecontext.app.ui.navigation.destinations

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.rousecontext.app.ui.navigation.ConfigureNavBar
import com.rousecontext.app.ui.navigation.Routes
import com.rousecontext.app.ui.screens.WelcomeScreen
import com.rousecontext.app.ui.viewmodels.OnboardingState
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
            // If the device is already onboarded when this
            // composable enters (e.g. returning user whose cert
            // store has a subdomain), skip straight past the
            // NotificationPreferences step to HOME. First-time
            // users see Welcome and then advance via the Get
            // Started button to NOTIFICATION_PREFERENCES.
            if (state is OnboardingState.Onboarded) {
                navController.navigate(Routes.HOME) {
                    popUpTo(Routes.ONBOARDING) {
                        inclusive = true
                    }
                    launchSingleTop = true
                }
            }
        }

        when (state) {
            is OnboardingState.Checking,
            is OnboardingState.NotOnboarded -> {
                WelcomeScreen(
                    onGetStarted = {
                        navController.navigate(
                            Routes.NOTIFICATION_PREFERENCES
                        )
                    }
                )
            }
            is OnboardingState.Onboarded,
            is OnboardingState.RateLimited,
            is OnboardingState.Failed -> {
                // Non-blocking: LaunchedEffect above
                // navigates to Home immediately.
                // Show nothing while navigation is in flight.
            }
        }
    }
}
