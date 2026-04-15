package com.rousecontext.app.ui.navigation.destinations

import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.DisposableEffect
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
import com.rousecontext.app.ui.screens.SetupMode
import com.rousecontext.app.ui.screens.UsageSetupContent
import com.rousecontext.app.ui.viewmodels.UsageSetupViewModel
import org.koin.androidx.compose.koinViewModel

@Suppress("LongMethod")
fun NavGraphBuilder.usageSetupDestination(navController: NavController) {
    composable(
        route = Routes.USAGE_SETUP,
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
                stringResource(R.string.destination_title_usage_settings)
            } else {
                stringResource(R.string.destination_title_usage)
            },
            showBackButton = true,
            onBackPressed = { navController.popBackStack() }
        )
        val viewModel: UsageSetupViewModel = koinViewModel()
        val state by viewModel.state.collectAsState()
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
        UsageSetupContent(
            state = state,
            mode = mode,
            onGrantAccess = {
                val intent = Intent(
                    Settings.ACTION_USAGE_ACCESS_SETTINGS
                )
                navController.context.startActivity(intent)
            },
            onEnable = {
                if (mode == SetupMode.SETTINGS) {
                    navController.popBackStack()
                } else {
                    if (viewModel.enable()) {
                        navController.navigate(
                            Routes.integrationSetup(
                                UsageSetupViewModel
                                    .INTEGRATION_ID
                            )
                        ) {
                            popUpTo(Routes.ADD_INTEGRATION) {
                                inclusive = true
                            }
                        }
                    }
                }
            },
            onCancel = {
                if (mode == SetupMode.SETTINGS) {
                    navController.popBackStack()
                } else {
                    navController.navigate(
                        Routes.integrationManage(
                            UsageSetupViewModel
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
