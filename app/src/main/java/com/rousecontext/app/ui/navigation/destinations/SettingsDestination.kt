package com.rousecontext.app.ui.navigation.destinations

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.rousecontext.app.support.BatteryOptimization
import com.rousecontext.app.support.BugReportUriBuilder
import com.rousecontext.app.ui.navigation.ConfigureNavBar
import com.rousecontext.app.ui.navigation.Routes
import com.rousecontext.app.ui.navigation.tabSlideDirection
import com.rousecontext.app.ui.screens.SettingsContent
import com.rousecontext.app.ui.viewmodels.SettingsViewModel
import com.rousecontext.work.CertRenewalScheduler
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/** User-facing privacy page on the Jekyll docs site (see `docs/user/privacy.md`). */
internal const val PRIVACY_URL = "https://rousecontext.com/privacy"

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
            title = stringResource(R.string.destination_title_settings),
            showBottomBar = true
        )
        SettingsDestinationContent(
            onOpenBackgroundDelivery = {
                navController.navigate(Routes.BACKGROUND_DELIVERY_SETTINGS)
            }
        )
    }
}

/**
 * Composable body of [settingsDestination]. Extracted so the top-level
 * function stays inside detekt's `LongMethod` limit while still wiring the
 * view-model, bug-report URI builder, privacy page link, and debug-only
 * "Renew cert now" callback into [SettingsContent].
 */
@Composable
private fun SettingsDestinationContent(onOpenBackgroundDelivery: () -> Unit) {
    val viewModel: SettingsViewModel = koinViewModel()
    val state by viewModel.state.collectAsState()
    val showAll by viewModel.showAllMcpMessages.collectAsState()
    val bugReportUriBuilder: BugReportUriBuilder = koinInject()
    val settingsContext = LocalContext.current
    // Re-read battery-optimization status when returning from the system
    // settings screen the "Fix this" button opens (#453).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner.lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    SettingsContent(
        state = state.copy(showAllMcpMessages = showAll),
        onIdleTimeoutChanged = viewModel::setIdleTimeout,
        onQuickDisconnectChanged = viewModel::setQuickDisconnect,
        onDisableTimeoutToggled = viewModel::setDisableTimeout,
        onFixBatteryOptimization = {
            startActivitySafely(
                settingsContext,
                BatteryOptimization.requestExemptionIntent(settingsContext)
            )
        },
        onPostSessionModeChanged = viewModel::setPostSessionMode,
        onShowAllMcpMessagesChanged = viewModel::setShowAllMcpMessages,
        onThemeModeChanged = viewModel::setThemeMode,
        onSecurityCheckIntervalChanged = viewModel::setSecurityCheckInterval,
        onGenerateNewAddress = viewModel::rotateSecret,
        onOpenBackgroundDelivery = onOpenBackgroundDelivery,
        onAcknowledgeAlert = viewModel::acknowledgeAlert,
        onReportBug = { openUriSafely(settingsContext, bugReportUriBuilder.build()) },
        onOpenPrivacy = { openUriSafely(settingsContext, Uri.parse(PRIVACY_URL)) },
        // Debug-only button (gated in SettingsContent via BuildConfig.DEBUG).
        // Route through the scheduler so WorkManager wiring stays in :work.
        onRenewCertNow = { CertRenewalScheduler.enqueueOneShot(settingsContext) },
        onRetry = viewModel::refresh
    )
}

/**
 * Fire an `ACTION_VIEW` intent, swallowing [ActivityNotFoundException] when
 * the device has no browser installed. Shared by the Report Bug and Privacy
 * rows so their wiring stays declarative.
 */
private fun openUriSafely(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No browser available; silently ignore.
    }
}

/**
 * Launch an arbitrary system [Intent], swallowing [ActivityNotFoundException]
 * for the rare device/ROM that doesn't expose the target screen. Used for the
 * battery-optimization request dialog behind "Fix this".
 */
internal fun startActivitySafely(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    } catch (_: ActivityNotFoundException) {
        // Settings screen not available on this device; silently ignore.
    }
}
