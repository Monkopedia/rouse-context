package com.rousecontext.app.ui.navigation.destinations

import com.rousecontext.app.ui.navigation.Routes
import com.rousecontext.app.ui.screens.SetupMode
import com.rousecontext.app.ui.viewmodels.HealthConnectSetupViewModel
import com.rousecontext.app.ui.viewmodels.NotificationSetupViewModel
import com.rousecontext.app.ui.viewmodels.OutreachSetupViewModel
import com.rousecontext.app.ui.viewmodels.UsageSetupViewModel

/**
 * Resolves the setup route for an integration id. Integrations that need a
 * dedicated permission/credential step get their own screen; everything else
 * uses the generic [Routes.integrationSetup].
 *
 * Shared by the add-integration picker and the #474 auto-resume path so both
 * land on the same screen when entering an integration's setup flow.
 */
internal fun setupRouteForIntegration(id: String): String = when (id) {
    HealthConnectSetupViewModel.HEALTH_INTEGRATION_ID ->
        Routes.healthConnectSetup(SetupMode.SETUP)
    NotificationSetupViewModel.INTEGRATION_ID ->
        Routes.notificationSetup(SetupMode.SETUP)
    OutreachSetupViewModel.INTEGRATION_ID ->
        Routes.outreachSetup(SetupMode.SETUP)
    UsageSetupViewModel.INTEGRATION_ID ->
        Routes.usageSetup(SetupMode.SETUP)
    else ->
        Routes.integrationSetup(id)
}
