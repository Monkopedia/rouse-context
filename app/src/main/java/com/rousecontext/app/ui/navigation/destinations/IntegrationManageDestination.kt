package com.rousecontext.app.ui.navigation.destinations

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.rousecontext.app.ui.navigation.Routes
import com.rousecontext.app.ui.screens.IntegrationManageContent
import com.rousecontext.app.ui.screens.SetupMode
import com.rousecontext.app.ui.viewmodels.HealthConnectSetupViewModel
import com.rousecontext.app.ui.viewmodels.IntegrationManageViewModel
import com.rousecontext.app.ui.viewmodels.NotificationSetupViewModel
import com.rousecontext.app.ui.viewmodels.OutreachSetupViewModel
import com.rousecontext.app.ui.viewmodels.UsageSetupViewModel
import org.koin.androidx.compose.koinViewModel

@Suppress("LongMethod")
fun NavGraphBuilder.integrationManageDestination(navController: NavController) {
    composable(
        route = Routes.INTEGRATION_MANAGE,
        arguments = listOf(
            navArgument("integrationId") {
                type = NavType.StringType
            }
        )
    ) { backStackEntry ->
        val integrationId = backStackEntry.arguments
            ?.getString("integrationId")
            ?: return@composable
        val viewModel: IntegrationManageViewModel =
            koinViewModel()
        LaunchedEffect(integrationId) {
            viewModel.loadIntegration(integrationId)
        }
        val state by viewModel.state.collectAsState()
        IntegrationManageContent(
            state = state,
            onAddClient = {
                navController.navigate(
                    Routes.integrationEnabled(integrationId)
                )
            },
            onViewAllClients = {
                navController.navigate(
                    Routes.allClients(integrationId)
                )
            },
            onEntryClick = { entryId ->
                navController.navigate(
                    Routes.auditDetail(entryId)
                )
            },
            onViewAllActivity = {
                navController.navigate(
                    Routes.audit(provider = integrationId)
                ) {
                    popUpTo(Routes.HOME)
                }
            },
            onSettings = {
                when (integrationId) {
                    HealthConnectSetupViewModel
                        .HEALTH_INTEGRATION_ID ->
                        navController.navigate(
                            Routes.healthConnectSetup(
                                SetupMode.SETTINGS
                            )
                        )
                    NotificationSetupViewModel.INTEGRATION_ID ->
                        navController.navigate(
                            Routes.notificationSetup(
                                SetupMode.SETTINGS
                            )
                        )
                    OutreachSetupViewModel.INTEGRATION_ID ->
                        navController.navigate(
                            Routes.outreachSetup(
                                SetupMode.SETTINGS
                            )
                        )
                    UsageSetupViewModel.INTEGRATION_ID ->
                        navController.navigate(
                            Routes.usageSetup(
                                SetupMode.SETTINGS
                            )
                        )
                }
            },
            onDisable = {
                viewModel.disable()
                navController.popBackStack()
            },
            onRevokeClient = { clientName ->
                viewModel.revokeClient(clientName)
            },
            onBack = { navController.popBackStack() }
        )
    }
}
