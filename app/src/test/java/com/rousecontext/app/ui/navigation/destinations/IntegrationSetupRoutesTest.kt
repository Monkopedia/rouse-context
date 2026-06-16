package com.rousecontext.app.ui.navigation.destinations

import com.rousecontext.app.ui.navigation.Routes
import com.rousecontext.app.ui.screens.SetupMode
import com.rousecontext.app.ui.viewmodels.HealthConnectSetupViewModel
import com.rousecontext.app.ui.viewmodels.NotificationSetupViewModel
import com.rousecontext.app.ui.viewmodels.OutreachSetupViewModel
import com.rousecontext.app.ui.viewmodels.UsageSetupViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks in the integration-id -> setup-route mapping shared by the add-integration
 * picker and the #474 auto-resume path. Both must resolve the same dedicated
 * setup screens (Health Connect, notifications, outreach, usage) so resuming
 * after the Background delivery detour lands on the right flow.
 */
class IntegrationSetupRoutesTest {

    @Test
    fun `maps specialized integrations to their dedicated setup routes`() {
        assertEquals(
            Routes.healthConnectSetup(SetupMode.SETUP),
            setupRouteForIntegration(HealthConnectSetupViewModel.HEALTH_INTEGRATION_ID)
        )
        assertEquals(
            Routes.notificationSetup(SetupMode.SETUP),
            setupRouteForIntegration(NotificationSetupViewModel.INTEGRATION_ID)
        )
        assertEquals(
            Routes.outreachSetup(SetupMode.SETUP),
            setupRouteForIntegration(OutreachSetupViewModel.INTEGRATION_ID)
        )
        assertEquals(
            Routes.usageSetup(SetupMode.SETUP),
            setupRouteForIntegration(UsageSetupViewModel.INTEGRATION_ID)
        )
    }

    @Test
    fun `maps an unknown integration to the generic setup route`() {
        assertEquals(
            Routes.integrationSetup("custom"),
            setupRouteForIntegration("custom")
        )
    }
}
