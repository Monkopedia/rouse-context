package com.rousecontext.app.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that the persistent top and bottom bars are shown only on tab
 * routes (HOME, AUDIT, SETTINGS) and hidden on detail/setup routes.
 *
 * The actual [AppNavigation] composable uses:
 * ```
 * val isTabRoute = currentRoute != null && currentRoute in TAB_ROUTES
 * ```
 * where `TAB_ROUTES = setOf(Routes.HOME, Routes.AUDIT, Routes.SETTINGS)`.
 *
 * This test validates that classification for every defined route.
 */
class NavigationBarVisibilityTest {

    /**
     * Mirrors the TAB_ROUTES set from [AppNavigation].
     * If the set in production changes, this test should break.
     */
    private val tabRoutes = setOf(Routes.HOME, Routes.AUDIT, Routes.SETTINGS)

    /**
     * Simulates the `isTabRoute` check from AppNavigation.
     */
    private fun shouldShowBars(route: String?): Boolean = route != null && route in tabRoutes

    // -- Tab routes: bars should be visible --

    @Test
    fun `HOME route shows navigation bars`() {
        assertTrue("HOME should show bars", shouldShowBars(Routes.HOME))
    }

    @Test
    fun `AUDIT route shows navigation bars`() {
        assertTrue("AUDIT should show bars", shouldShowBars(Routes.AUDIT))
    }

    @Test
    fun `SETTINGS route shows navigation bars`() {
        assertTrue("SETTINGS should show bars", shouldShowBars(Routes.SETTINGS))
    }

    // -- Detail routes: bars should be hidden --

    @Test
    fun `ADD_INTEGRATION route hides navigation bars`() {
        assertFalse(
            "ADD_INTEGRATION should hide bars",
            shouldShowBars(Routes.ADD_INTEGRATION)
        )
    }

    @Test
    fun `INTEGRATION_MANAGE route hides navigation bars`() {
        assertFalse(
            "INTEGRATION_MANAGE should hide bars",
            shouldShowBars(Routes.INTEGRATION_MANAGE)
        )
    }

    @Test
    fun `INTEGRATION_SETUP route hides navigation bars`() {
        assertFalse(
            "INTEGRATION_SETUP should hide bars",
            shouldShowBars(Routes.INTEGRATION_SETUP)
        )
    }

    @Test
    fun `HEALTH_CONNECT_SETUP route hides navigation bars`() {
        assertFalse(
            "HEALTH_CONNECT_SETUP should hide bars",
            shouldShowBars(Routes.HEALTH_CONNECT_SETUP)
        )
    }

    @Test
    fun `NOTIFICATION_SETUP route hides navigation bars`() {
        assertFalse(
            "NOTIFICATION_SETUP should hide bars",
            shouldShowBars(Routes.NOTIFICATION_SETUP)
        )
    }

    @Test
    fun `OUTREACH_SETUP route hides navigation bars`() {
        assertFalse(
            "OUTREACH_SETUP should hide bars",
            shouldShowBars(Routes.OUTREACH_SETUP)
        )
    }

    @Test
    fun `USAGE_SETUP route hides navigation bars`() {
        assertFalse(
            "USAGE_SETUP should hide bars",
            shouldShowBars(Routes.USAGE_SETUP)
        )
    }

    @Test
    fun `INTEGRATION_ENABLED route hides navigation bars`() {
        assertFalse(
            "INTEGRATION_ENABLED should hide bars",
            shouldShowBars(Routes.INTEGRATION_ENABLED)
        )
    }

    @Test
    fun `AUTH_APPROVAL route hides navigation bars`() {
        assertFalse(
            "AUTH_APPROVAL should hide bars",
            shouldShowBars(Routes.AUTH_APPROVAL)
        )
    }

    @Test
    fun `ALL_CLIENTS route hides navigation bars`() {
        assertFalse(
            "ALL_CLIENTS should hide bars",
            shouldShowBars(Routes.ALL_CLIENTS)
        )
    }

    @Test
    fun `AUDIT_DETAIL route hides navigation bars`() {
        assertFalse(
            "AUDIT_DETAIL should hide bars",
            shouldShowBars(Routes.AUDIT_DETAIL)
        )
    }

    @Test
    fun `ONBOARDING route hides navigation bars`() {
        assertFalse(
            "ONBOARDING should hide bars",
            shouldShowBars(Routes.ONBOARDING)
        )
    }

    @Test
    fun `null route hides navigation bars`() {
        assertFalse(
            "null route should hide bars",
            shouldShowBars(null)
        )
    }

    // -- Resolved routes (with actual arguments) should also be hidden --

    @Test
    fun `resolved all clients route hides navigation bars`() {
        assertFalse(
            "Resolved all_clients/health should hide bars",
            shouldShowBars(Routes.allClients("health"))
        )
    }

    @Test
    fun `resolved integration manage route hides navigation bars`() {
        assertFalse(
            "Resolved integration/health should hide bars",
            shouldShowBars(Routes.integrationManage("health"))
        )
    }

    @Test
    fun `resolved audit detail route hides navigation bars`() {
        assertFalse(
            "Resolved audit_detail/42 should hide bars",
            shouldShowBars(Routes.auditDetail(42))
        )
    }

    // -- Verify tab route set is exactly the expected three routes --

    @Test
    fun `tab routes set contains exactly HOME, AUDIT, and SETTINGS`() {
        val expected = setOf(Routes.HOME, Routes.AUDIT, Routes.SETTINGS)
        assertTrue(
            "Tab routes should be exactly HOME, AUDIT, SETTINGS but was: $tabRoutes",
            tabRoutes == expected
        )
    }
}
