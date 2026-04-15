package com.rousecontext.app.ui.navigation.destinations

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.rousecontext.app.R
import com.rousecontext.app.state.NotificationPermissionRefresher
import com.rousecontext.app.ui.navigation.ConfigureNavBar
import com.rousecontext.app.ui.navigation.Routes
import com.rousecontext.app.ui.navigation.TAB_INDEX
import com.rousecontext.app.ui.navigation.tabSlideDirection
import com.rousecontext.app.ui.screens.HomeDashboardContent
import com.rousecontext.app.ui.viewmodels.MainDashboardViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Suppress("LongMethod")
fun NavGraphBuilder.mainDashboardDestination(navController: NavController) {
    // Tab routes with horizontal slide transitions
    composable(
        Routes.HOME,
        enterTransition = {
            val fromRoute = initialState.destination.route
            if (fromRoute != null && fromRoute in TAB_INDEX) {
                val dir = tabSlideDirection(fromRoute, targetState.destination.route)
                slideInHorizontally(
                    initialOffsetX = { dir * (it / 4) }
                ) + fadeIn()
            } else {
                fadeIn()
            }
        },
        exitTransition = {
            val toRoute = targetState.destination.route
            if (toRoute != null && toRoute in TAB_INDEX) {
                val dir = tabSlideDirection(
                    initialState.destination.route,
                    toRoute
                )
                slideOutHorizontally(
                    targetOffsetX = { -dir * (it / 4) }
                ) + fadeOut()
            } else {
                fadeOut()
            }
        }
    ) {
        ConfigureNavBar(
            title = stringResource(R.string.destination_title_main_dashboard),
            showBottomBar = true
        )
        val viewModel: MainDashboardViewModel = koinViewModel()
        val state by viewModel.state.collectAsState()
        val context = LocalContext.current
        val refresher: NotificationPermissionRefresher =
            koinInject()
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    // Re-check on resume so revoking notifications
                    // from system settings updates the dashboard
                    // banner immediately when the user comes back.
                    refresher.refresh()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        LaunchedEffect(
            navController.currentBackStackEntry
                ?.lifecycle?.currentState
        ) {
            viewModel.refresh()
        }

        HomeDashboardContent(
            state = state,
            onAddIntegration = {
                navController.navigate(Routes.ADD_INTEGRATION)
            },
            onIntegrationClick = { id ->
                navController.navigate(
                    Routes.integrationManage(id)
                )
            },
            onViewAllActivity = {
                navController.navigate(Routes.AUDIT_BASE)
            },
            onOpenNotificationSettings = {
                // Open the per-app notification settings so the
                // user can re-enable. If the runtime permission
                // was permanently denied, this is the only path
                // back to a working state.
                val intent = Intent(
                    Settings.ACTION_APP_NOTIFICATION_SETTINGS
                ).apply {
                    putExtra(
                        Settings.EXTRA_APP_PACKAGE,
                        context.packageName
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            },
            onOpenSettings = {
                navController.navigate(Routes.SETTINGS) {
                    launchSingleTop = true
                }
            },
            onRetry = { viewModel.retry() }
        )
    }
}
