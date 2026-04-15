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
import com.rousecontext.app.ui.screens.AllClientsContent
import com.rousecontext.app.ui.viewmodels.IntegrationManageViewModel
import org.koin.androidx.compose.koinViewModel

fun NavGraphBuilder.allClientsDestination(navController: NavController) {
    composable(
        route = Routes.ALL_CLIENTS,
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
        AllClientsContent(
            integrationName = state.integrationName,
            clients = state.authorizedClients,
            onRevokeClient = { clientName ->
                viewModel.revokeClient(clientName)
            },
            onBack = { navController.popBackStack() }
        )
    }
}
