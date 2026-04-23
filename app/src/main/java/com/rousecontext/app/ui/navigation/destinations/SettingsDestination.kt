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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.rousecontext.app.R
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
            title = stringResource(R.string.destination_title_settings),
            showBottomBar = true
        )
        SettingsDestinationContent()
    }
}

/**
 * Composable body of [settingsDestination]. Extracted so the top-level
 * function stays inside detekt's `LongMethod` limit while still wiring the
 * view-model, bug-report URI builder, privacy page link, and debug-only
 * "Renew cert now" callback into [SettingsContent].
 */
@Composable
private fun SettingsDestinationContent() {
    val viewModel: SettingsViewModel = koinViewModel()
    val state by viewModel.state.collectAsState()
    val showAll by viewModel.showAllMcpMessages.collectAsState()
    val bugReportUriBuilder: BugReportUriBuilder = koinInject()
    val settingsContext = LocalContext.current
    SettingsContent(
        state = state.copy(showAllMcpMessages = showAll),
        onIdleTimeoutChanged = viewModel::setIdleTimeout,
        onPostSessionModeChanged = viewModel::setPostSessionMode,
        onShowAllMcpMessagesChanged = viewModel::setShowAllMcpMessages,
        onThemeModeChanged = viewModel::setThemeMode,
        onSecurityCheckIntervalChanged = viewModel::setSecurityCheckInterval,
        onGenerateNewAddress = viewModel::rotateSecret,
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
