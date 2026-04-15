package com.rousecontext.app.ui.navigation.destinations

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.rousecontext.app.R
import com.rousecontext.app.ui.navigation.ConfigureNavBar
import com.rousecontext.app.ui.navigation.Routes
import com.rousecontext.app.ui.screens.SettingUpContent
import com.rousecontext.app.ui.screens.SettingUpState
import com.rousecontext.app.ui.screens.SettingUpVariant
import com.rousecontext.app.ui.viewmodels.IntegrationSetupState
import com.rousecontext.app.ui.viewmodels.IntegrationSetupViewModel
import org.koin.androidx.compose.koinViewModel

@Suppress("LongMethod")
fun NavGraphBuilder.integrationSetupDestination(navController: NavController) {
    composable(
        route = Routes.INTEGRATION_SETUP,
        arguments = listOf(
            navArgument("integrationId") {
                type = NavType.StringType
            }
        )
    ) { backStackEntry ->
        ConfigureNavBar(
            title = stringResource(R.string.destination_title_integration_setup),
            showBackButton = true,
            onBackPressed = { navController.popBackStack() }
        )
        val integrationId = backStackEntry.arguments
            ?.getString("integrationId")
            ?: return@composable
        val viewModel: IntegrationSetupViewModel =
            koinViewModel()
        LaunchedEffect(integrationId) {
            viewModel.startSetup(integrationId)
        }
        val state by viewModel.state.collectAsState()
        when (state) {
            is IntegrationSetupState.Complete -> {
                LaunchedEffect(Unit) {
                    navController.navigate(
                        Routes.integrationEnabled(integrationId)
                    ) {
                        popUpTo(Routes.ADD_INTEGRATION) {
                            inclusive = true
                        }
                    }
                }
            }
            is IntegrationSetupState.Provisioning -> {
                SettingUpContent(
                    state = (
                        state as
                            IntegrationSetupState.Provisioning
                        ).settingUpState,
                    onCancel = {
                        navController.navigate(
                            Routes.integrationManage(
                                integrationId
                            )
                        ) {
                            popUpTo(Routes.HOME)
                        }
                    }
                )
            }
            is IntegrationSetupState.Failed -> {
                SettingUpContent(
                    state = SettingUpState(
                        variant = SettingUpVariant.Failed(
                            (
                                state as
                                    IntegrationSetupState.Failed
                                ).message
                        )
                    ),
                    // #76: integration may not be registered
                    // if provisioning failed pre-Complete, so
                    // avoid IntegrationManage and pop to HOME.
                    onCancel = {
                        navController.popBackStack(
                            Routes.HOME,
                            inclusive = false
                        )
                    },
                    // #108: explicit Retry re-runs the failed step
                    // without requiring the user to back out.
                    onRetry = { viewModel.retry() }
                )
            }
            is IntegrationSetupState.RateLimited -> {
                SettingUpContent(
                    state = SettingUpState(
                        variant = SettingUpVariant.RateLimited(
                            (
                                state as
                                    IntegrationSetupState
                                        .RateLimited
                                ).retryDate
                        )
                    ),
                    // #76: same reasoning as Failed above.
                    onCancel = {
                        navController.popBackStack(
                            Routes.HOME,
                            inclusive = false
                        )
                    }
                )
            }
            is IntegrationSetupState.Idle -> {
                // Waiting for setup to begin
            }
        }
    }
}
