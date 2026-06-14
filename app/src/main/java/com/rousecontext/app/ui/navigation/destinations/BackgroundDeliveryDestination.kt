package com.rousecontext.app.ui.navigation.destinations

import android.content.ActivityNotFoundException
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.rousecontext.app.delivery.BackgroundDelivery
import com.rousecontext.app.delivery.DistributorOption
import com.rousecontext.app.ui.navigation.ConfigureNavBar
import com.rousecontext.app.ui.navigation.Routes
import com.rousecontext.app.ui.screens.BackgroundDeliveryScreen
import com.rousecontext.app.ui.viewmodels.BackgroundDeliveryViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * "Background delivery" UnifiedPush distributor picker (issue #463). Foss-only
 * in practice (the google [BackgroundDelivery] is a no-op and nothing routes
 * here), but registered unconditionally so the shared NavHost stays
 * flavor-agnostic.
 *
 * Re-scans installed distributors on `ON_RESUME` so returning from a store
 * install refreshes the list. In onboarding, both picking a distributor and
 * "Skip for now" advance to notification preferences (deferred activation —
 * registration fires later, when an endpoint arrives via the push receiver).
 */
fun NavGraphBuilder.backgroundDeliveryDestination(navController: NavController) {
    composable(
        route = Routes.BACKGROUND_DELIVERY,
        arguments = listOf(
            navArgument(Routes.SETTINGS_ARG) {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }
        )
    ) { backStackEntry ->
        ConfigureNavBar(title = "", showTopBar = false, showBottomBar = false)
        val settingsMode = backStackEntry.arguments?.getString(Routes.SETTINGS_ARG) == "true"
        BackgroundDeliveryDestinationContent(navController, settingsMode)
    }
}

/**
 * Composable body of [backgroundDeliveryDestination], extracted so the NavGraph
 * lambda stays within detekt's method-length limit.
 */
@Composable
private fun BackgroundDeliveryDestinationContent(
    navController: NavController,
    settingsMode: Boolean
) {
    val viewModel: BackgroundDeliveryViewModel = koinViewModel()
    val delivery: BackgroundDelivery = koinInject()
    val context = LocalContext.current
    val rows by viewModel.rows.collectAsState()

    // Re-scan installed distributors when the user returns (e.g. after
    // installing one from the store the "install" rows deep-link to).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner.lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.rescan()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val advance = {
        if (settingsMode) {
            navController.popBackStack()
        } else {
            navController.navigate(Routes.NOTIFICATION_PREFERENCES)
        }
        Unit
    }

    BackgroundDeliveryScreen(
        rows = rows,
        settingsMode = settingsMode,
        onSelect = { option ->
            when (option.kind) {
                DistributorOption.Kind.INSTALLED,
                DistributorOption.Kind.ACTIVE -> {
                    viewModel.select(option.id)
                    advance()
                }
                DistributorOption.Kind.INSTALL_NTFY,
                DistributorOption.Kind.INSTALL_OTHER -> {
                    delivery.installIntent(option)?.let { intent ->
                        try {
                            context.startActivity(intent)
                        } catch (_: ActivityNotFoundException) {
                            // No store/browser available; stay on the picker.
                        }
                    }
                }
            }
        },
        onSkip = advance,
        onBack = { navController.popBackStack() }
    )
}
