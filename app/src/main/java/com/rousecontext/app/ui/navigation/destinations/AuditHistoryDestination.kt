package com.rousecontext.app.ui.navigation.destinations

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import com.rousecontext.app.ui.navigation.tabSlideDirection
import com.rousecontext.app.ui.screens.AuditHistoryContent
import com.rousecontext.app.ui.screens.ProviderFilterOption
import com.rousecontext.app.ui.viewmodels.AuditHistoryViewModel
import org.koin.androidx.compose.koinViewModel

private fun nullableStringArg(name: String) = navArgument(name) {
    type = NavType.StringType
    nullable = true
    defaultValue = null
}

@Suppress("LongMethod")
fun NavGraphBuilder.auditHistoryDestination(navController: NavController) {
    composable(
        Routes.AUDIT,
        arguments = listOf(
            nullableStringArg("provider"),
            nullableStringArg("scrollToCallId")
        ),
        enterTransition = {
            val dir = tabSlideDirection(
                initialState.destination.route,
                targetState.destination.route
            )
            slideInHorizontally(
                initialOffsetX = { dir * (it / 4) }
            ) + fadeIn()
        },
        exitTransition = {
            val dir = tabSlideDirection(
                initialState.destination.route,
                targetState.destination.route
            )
            slideOutHorizontally(
                targetOffsetX = { -dir * (it / 4) }
            ) + fadeOut()
        }
    ) { backStackEntry ->
        ConfigureNavBar(
            title = stringResource(R.string.destination_title_audit_history),
            showBottomBar = true
        )
        val viewModel: AuditHistoryViewModel = koinViewModel()
        val args = backStackEntry.arguments
        val providerArg = args?.getString("provider")
        // Notification-tap extra: per-tool-call scroll target (#347).
        // The session-window override on summary taps was removed in #370 —
        // summary taps now land at the default audit view.
        val scrollToArg = args?.getString("scrollToCallId")?.toLongOrNull()
        LaunchedEffect(providerArg) {
            if (providerArg != null) {
                viewModel.setProviderFilter(ProviderFilterOption.Specific(providerArg))
            }
        }
        LaunchedEffect(scrollToArg) {
            if (scrollToArg != null) {
                viewModel.requestScrollTo(scrollToArg)
            }
        }
        val state by viewModel.state.collectAsState()
        AuditHistoryContent(
            state = state,
            onProviderFilterChanged =
            viewModel::setProviderFilter,
            onDateFilterChanged = viewModel::setDateFilter,
            onClearHistory = viewModel::clearHistory,
            onEntryClick = { entryId ->
                navController.navigate(
                    Routes.auditDetail(entryId)
                )
            },
            onRetry = { viewModel.retry() }
        )
    }
}
