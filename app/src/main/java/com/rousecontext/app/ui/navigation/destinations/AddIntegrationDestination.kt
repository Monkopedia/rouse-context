package com.rousecontext.app.ui.navigation.destinations

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.rousecontext.app.R
import com.rousecontext.app.auth.DeviceCredentialProvider
import com.rousecontext.app.delivery.BackgroundDelivery
import com.rousecontext.app.state.PendingIntegrationSetup
import com.rousecontext.app.state.deliveryNeedsSetupBeforeIntegration
import com.rousecontext.app.ui.navigation.ConfigureNavBar
import com.rousecontext.app.ui.navigation.Routes
import com.rousecontext.app.ui.screens.AddIntegrationPickerContent
import com.rousecontext.app.ui.viewmodels.AddIntegrationViewModel
import com.rousecontext.tunnel.CertProvisioningFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
        val credentialProvider: DeviceCredentialProvider = koinInject()
        val appScope: CoroutineScope = koinInject(named("appScope"))
        val delivery: BackgroundDelivery = koinInject()
        val pendingSetup: PendingIntegrationSetup = koinInject()
        val activation by delivery.activation.collectAsState()
        AddIntegrationPickerContent(
            integrations = integrations,
            onSetUp = { id ->
                // #474: a foss device that skipped Background delivery has no
                // push endpoint and isn't registered yet, so setup would block
                // silently in IntegrationSetupViewModel.awaitRegistrationIfNeeded.
                // Remember the integration and redirect to the picker; the
                // picker auto-resumes here once a distributor is chosen. On
                // google/registered foss this is a no-op (NotApplicable/Active).
                if (deliveryNeedsSetupBeforeIntegration(activation)) {
                    pendingSetup.remember(id)
                    navController.navigate(Routes.BACKGROUND_DELIVERY_BASE)
                } else {
                    // Kick off cert provisioning in the background while the
                    // user configures the integration. Survives navigation
                    // since it uses the app-scoped coroutine scope.
                    appScope.launch {
                        provisionCertsInBackground(credentialProvider, certProvisioningFlow)
                    }
                    navController.navigate(setupRouteForIntegration(id))
                }
            }
        )
    }
}

/**
 * Best-effort background cert provisioning kicked off when the user picks an
 * integration. Extracted from the destination so the cancellation/failure
 * contract is unit-testable.
 */
internal suspend fun provisionCertsInBackground(
    credentialProvider: DeviceCredentialProvider,
    certProvisioningFlow: CertProvisioningFlow
) {
    try {
        val credential = credentialProvider.forProvisioning()
        if (credential != null) {
            certProvisioningFlow.execute(credential)
        }
    } catch (e: CancellationException) {
        // MUST stay above the broad catch (issue #662). #660 made
        // CertProvisioningFlow.execute propagate cancellation instead of
        // reporting StorageFailed; a bare `catch (Exception)` here converts it
        // straight back one frame above the fix. "integrationSetup will retry"
        // is an argument about a provisioning FAILURE -- it is not an argument
        // about cancellation, because a cancelled screen is gone and will not
        // retry.
        throw e
    } catch (_: Exception) {
        // Best-effort; integrationSetup will retry
    }
}
