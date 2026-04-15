package com.rousecontext.app.ui.navigation.destinations

import android.content.Intent
import android.net.Uri
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
import com.rousecontext.app.ui.screens.OutreachSetupContent
import com.rousecontext.app.ui.screens.SetupMode
import com.rousecontext.app.ui.viewmodels.OutreachSetupViewModel
import org.koin.androidx.compose.koinViewModel

@Suppress("LongMethod")
fun NavGraphBuilder.outreachSetupDestination(navController: NavController) {
    composable(
        route = Routes.OUTREACH_SETUP,
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
                stringResource(R.string.destination_title_outreach_settings)
            } else {
                stringResource(R.string.destination_title_outreach)
            },
            showBackButton = true,
            onBackPressed = { navController.popBackStack() }
        )
        val viewModel: OutreachSetupViewModel =
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
        OutreachSetupContent(
            state = state,
            mode = mode,
            isDirty = isDirty,
            onDndToggled = viewModel::setDndToggled,
            onGrantDnd = {
                val intent = Intent(
                    Settings
                        .ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
                )
                navController.context.startActivity(intent)
            },
            onDirectLaunchToggled = viewModel::setDirectLaunchEnabled,
            onGrantOverlay = {
                val ctx = navController.context
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${ctx.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
            },
            onEnable = {
                viewModel.enable()
                navController.navigate(
                    Routes.integrationSetup(
                        OutreachSetupViewModel
                            .INTEGRATION_ID
                    )
                ) {
                    popUpTo(Routes.ADD_INTEGRATION) {
                        inclusive = true
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
                            OutreachSetupViewModel
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
