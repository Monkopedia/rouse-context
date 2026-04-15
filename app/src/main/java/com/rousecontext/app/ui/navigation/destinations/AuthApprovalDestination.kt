package com.rousecontext.app.ui.navigation.destinations

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.rousecontext.app.state.NotificationPermissionMonitor
import com.rousecontext.app.state.NotificationPermissionRefresher
import com.rousecontext.app.ui.navigation.ConfigureNavBar
import com.rousecontext.app.ui.navigation.Routes
import com.rousecontext.app.ui.screens.AuthorizationApprovalContent
import com.rousecontext.app.ui.viewmodels.AuthorizationApprovalViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Suppress("LongMethod")
fun NavGraphBuilder.authApprovalDestination(navController: NavController) {
    composable(Routes.AUTH_APPROVAL) {
        ConfigureNavBar(
            title = "Approve AI Client",
            showTopBar = true,
            showBackButton = true,
            onBackPressed = { navController.popBackStack() }
        )
        val viewModel: AuthorizationApprovalViewModel =
            koinViewModel()

        // Re-prompt for notifications if the user landed here
        // without having granted them. They've clearly opted into
        // the approval flow, so don't strand them: the screen
        // remains usable whether they accept or deny. Issue #93.
        val approvalContext = LocalContext.current
        val approvalRefresher: NotificationPermissionRefresher =
            koinInject()
        val approvalPermissionLauncher =
            rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { _ ->
                approvalRefresher.refresh()
            }
        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.TIRAMISU &&
                !NotificationPermissionMonitor
                    .areNotificationsEnabled(approvalContext)
            ) {
                approvalPermissionLauncher.launch(
                    Manifest.permission.POST_NOTIFICATIONS
                )
            }
        }

        val requests by viewModel.pendingRequests
            .collectAsState()
        val uiState by viewModel.uiState.collectAsState()

        // Track the integration from the last approved request
        // so we can navigate to its manage page.
        val lastApprovedIntegration =
            remember {
                mutableStateOf<String?>(null)
            }

        // When no pending requests remain after approval,
        // navigate to the manage page for that integration.
        val hadRequests = remember {
            mutableStateOf(false)
        }
        if (requests.isNotEmpty()) {
            hadRequests.value = true
        }
        if (hadRequests.value && requests.isEmpty()) {
            LaunchedEffect(Unit) {
                val target =
                    lastApprovedIntegration.value
                if (target != null) {
                    navController.navigate(
                        Routes.integrationManage(target)
                    ) {
                        popUpTo(Routes.HOME)
                    }
                } else {
                    navController.popBackStack()
                }
            }
        }

        AuthorizationApprovalContent(
            uiState = uiState,
            onApprove = { displayCode ->
                // Capture the integration before approving
                // (which removes the request from the list).
                val req = requests.find {
                    it.displayCode == displayCode
                }
                if (req != null) {
                    lastApprovedIntegration.value =
                        req.integration
                }
                viewModel.approve(displayCode)
            },
            onDeny = viewModel::deny,
            onRetry = viewModel::retry
        )
    }
}
