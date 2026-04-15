package com.rousecontext.app.ui.navigation.destinations

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.rousecontext.app.state.NotificationPermissionRefresher
import com.rousecontext.app.ui.navigation.ConfigureNavBar
import com.rousecontext.app.ui.navigation.Routes
import com.rousecontext.app.ui.screens.NotificationPreferencesScreen
import com.rousecontext.app.ui.viewmodels.NotificationPreferencesViewModel
import com.rousecontext.app.ui.viewmodels.OnboardingViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

fun NavGraphBuilder.notificationPreferencesDestination(navController: NavController) {
    composable(Routes.NOTIFICATION_PREFERENCES) {
        ConfigureNavBar(
            title = "",
            showTopBar = false,
            showBottomBar = false
        )
        val prefsViewModel: NotificationPreferencesViewModel =
            koinViewModel()
        val onboardingViewModel: OnboardingViewModel =
            koinViewModel()
        val state by prefsViewModel.state.collectAsState()
        val refresher: NotificationPermissionRefresher =
            koinInject()
        val continueToHome = {
            prefsViewModel.persistSelection()
            // Kick off relay/FCM registration in the background now
            // that the user has completed the one-time onboarding
            // preferences. This mirrors the old Get-Started behaviour
            // but delayed until after preferences are set.
            onboardingViewModel.startOnboarding()
            navController.navigate(Routes.HOME) {
                popUpTo(Routes.ONBOARDING) {
                    inclusive = true
                }
                launchSingleTop = true
            }
        }
        val notificationPermissionLauncher =
            rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { _ ->
                // Refresh so the dashboard banner reflects the new
                // state on next render, then continue — navigating
                // only after the system dialog dismisses prevents the
                // home screen from rendering behind the prompt.
                refresher.refresh()
                continueToHome()
            }
        NotificationPreferencesScreen(
            state = state,
            onModeSelected = prefsViewModel::select,
            onRequestNotificationPermission = {
                // On Android 13+ the launcher callback drives the
                // subsequent navigation. On older devices there is
                // no runtime permission and no system dialog, so
                // navigate directly.
                if (Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.TIRAMISU
                ) {
                    notificationPermissionLauncher.launch(
                        Manifest.permission.POST_NOTIFICATIONS
                    )
                } else {
                    continueToHome()
                }
            },
            onContinue = continueToHome,
            onBack = { navController.popBackStack() }
        )
    }
}
