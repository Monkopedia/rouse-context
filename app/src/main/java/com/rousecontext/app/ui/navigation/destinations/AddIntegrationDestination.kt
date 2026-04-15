package com.rousecontext.app.ui.navigation.destinations

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.google.firebase.auth.FirebaseAuth
import com.rousecontext.app.R
import com.rousecontext.app.ui.navigation.ConfigureNavBar
import com.rousecontext.app.ui.navigation.Routes
import com.rousecontext.app.ui.screens.AddIntegrationPickerContent
import com.rousecontext.app.ui.screens.SetupMode
import com.rousecontext.app.ui.viewmodels.AddIntegrationViewModel
import com.rousecontext.app.ui.viewmodels.HealthConnectSetupViewModel
import com.rousecontext.app.ui.viewmodels.NotificationSetupViewModel
import com.rousecontext.app.ui.viewmodels.OutreachSetupViewModel
import com.rousecontext.app.ui.viewmodels.UsageSetupViewModel
import com.rousecontext.tunnel.CertProvisioningFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.qualifier.named

@Suppress("LongMethod")
fun NavGraphBuilder.addIntegrationDestination(navController: NavController) {
    composable(Routes.ADD_INTEGRATION) {
        ConfigureNavBar(
            title = stringResource(R.string.destination_title_add_integration),
            showBackButton = true,
            onBackPressed = { navController.popBackStack() }
        )
        val viewModel: AddIntegrationViewModel = koinViewModel()
        val integrations by viewModel.pickerIntegrations
            .collectAsState()
        val certProvisioningFlow: CertProvisioningFlow = koinInject()
        val appScope: CoroutineScope = koinInject(named("appScope"))
        AddIntegrationPickerContent(
            integrations = integrations,
            onSetUp = { id ->
                // Kick off cert provisioning in the background while
                // user configures the integration. Survives navigation
                // since it uses the app-scoped coroutine scope.
                appScope.launch {
                    try {
                        val token = FirebaseAuth.getInstance()
                            .currentUser?.getIdToken(false)
                            ?.await()?.token
                        if (token != null) {
                            certProvisioningFlow.execute(token)
                        }
                    } catch (_: Exception) {
                        // Best-effort; integrationSetup will retry
                    }
                }
                when (id) {
                    HealthConnectSetupViewModel
                        .HEALTH_INTEGRATION_ID ->
                        navController.navigate(
                            Routes.healthConnectSetup(
                                SetupMode.SETUP
                            )
                        )
                    NotificationSetupViewModel.INTEGRATION_ID ->
                        navController.navigate(
                            Routes.notificationSetup(
                                SetupMode.SETUP
                            )
                        )
                    OutreachSetupViewModel.INTEGRATION_ID ->
                        navController.navigate(
                            Routes.outreachSetup(
                                SetupMode.SETUP
                            )
                        )
                    UsageSetupViewModel.INTEGRATION_ID ->
                        navController.navigate(
                            Routes.usageSetup(
                                SetupMode.SETUP
                            )
                        )
                    else ->
                        navController.navigate(
                            Routes.integrationSetup(id)
                        )
                }
            }
        )
    }
}
