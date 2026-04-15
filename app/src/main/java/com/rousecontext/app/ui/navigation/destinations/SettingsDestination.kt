package com.rousecontext.app.ui.navigation.destinations

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.rousecontext.app.support.BugReportUriBuilder
import com.rousecontext.app.ui.navigation.ConfigureNavBar
import com.rousecontext.app.ui.navigation.Routes
import com.rousecontext.app.ui.navigation.tabSlideDirection
import com.rousecontext.app.ui.screens.SettingsContent
import com.rousecontext.app.ui.viewmodels.SettingsViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.settingsDestination(navController: NavController) {
    composable(
        Routes.SETTINGS,
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
    ) {
        ConfigureNavBar(
            title = "Settings",
            showBottomBar = true
        )
        val viewModel: SettingsViewModel = koinViewModel()
        val state by viewModel.state.collectAsState()
        val showAll by viewModel.showAllMcpMessages.collectAsState()
        val bugReportUriBuilder: BugReportUriBuilder = koinInject()
        val settingsContext = LocalContext.current
        SettingsContent(
            state = state.copy(showAllMcpMessages = showAll),
            onIdleTimeoutChanged = viewModel::setIdleTimeout,
            onPostSessionModeChanged =
            viewModel::setPostSessionMode,
            onShowAllMcpMessagesChanged =
            viewModel::setShowAllMcpMessages,
            onThemeModeChanged = viewModel::setThemeMode,
            onSecurityCheckIntervalChanged =
            viewModel::setSecurityCheckInterval,
            onGenerateNewAddress = viewModel::rotateSecret,
            onAcknowledgeAlert = viewModel::acknowledgeAlert,
            onReportBug = {
                val uri = bugReportUriBuilder.build()
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    settingsContext.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    // No browser available; silently ignore.
                }
            },
            onRetry = viewModel::refresh
        )
    }
}
