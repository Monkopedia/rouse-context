package com.rousecontext.app.ui.navigation.destinations

import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.rousecontext.app.R
import com.rousecontext.app.ui.navigation.ConfigureNavBar
import com.rousecontext.app.ui.navigation.Routes
import com.rousecontext.app.ui.screens.NotificationSetupContent
import com.rousecontext.app.ui.screens.SetupMode
import com.rousecontext.app.ui.viewmodels.NotificationSetupViewModel
import org.koin.androidx.compose.koinViewModel

@Suppress("LongMethod")
fun NavGraphBuilder.notificationSetupDestination(navController: NavController) {
    composable(
        route = Routes.NOTIFICATION_SETUP,
        arguments = listOf(
            navArgument("mode") {
                type = NavType.StringType
            }
        )
    ) { backStackEntry ->
        val modeArg = backStackEntry.arguments
            ?.getString("mode")
        val mode = if (modeArg == SetupMode.SETTINGS.name) {
            SetupMode.SETTINGS
        } else {
            SetupMode.SETUP
        }
        ConfigureNavBar(
            title = if (mode == SetupMode.SETTINGS) {
                stringResource(R.string.destination_title_notification_settings)
            } else {
                stringResource(R.string.destination_title_notification_access)
            },
            showBackButton = true,
            onBackPressed = { navController.popBackStack() }
        )
        val viewModel: NotificationSetupViewModel =
            koinViewModel()
        LaunchedEffect(mode) {
            viewModel.initForMode(mode)
        }
        val state by viewModel.state.collectAsState()
        val isDirty by viewModel.isDirty.collectAsState()
        val lifecycleOwner = LocalLifecycleOwner.current
        val lifecycle = lifecycleOwner.lifecycle
        DisposableEffect(lifecycle) {
            val observer =
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        viewModel.refreshPermission()
                    }
                }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        NotificationSetupContent(
            state = state,
            mode = mode,
            isDirty = isDirty,
            onGrantAccess = {
                val intent = Intent(
                    Settings
                        .ACTION_NOTIFICATION_LISTENER_SETTINGS
                )
                navController.context.startActivity(intent)
            },
            onRetentionChanged = viewModel::setRetentionDays,
            onAllowActionsChanged = viewModel::setAllowActions,
            onEnable = {
                if (viewModel.enable()) {
                    navController.navigate(
                        Routes.integrationSetup(
                            NotificationSetupViewModel
                                .INTEGRATION_ID
                        )
                    ) {
                        popUpTo(Routes.ADD_INTEGRATION) {
                            inclusive = true
                        }
                    }
                }
            },
            onSave = {
                viewModel.saveSettings()
            },
            onCancel = {
                if (mode == SetupMode.SETTINGS) {
                    navController.popBackStack()
                } else {
                    navController.navigate(
                        Routes.integrationManage(
                            NotificationSetupViewModel
                                .INTEGRATION_ID
                        )
                    ) {
                        popUpTo(Routes.HOME)
                    }
                }
            }
        )
    }
}
