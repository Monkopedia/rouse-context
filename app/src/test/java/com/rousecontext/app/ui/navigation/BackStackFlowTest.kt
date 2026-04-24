package com.rousecontext.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Models the back stack for the main setup/management flows and verifies
 * that back-press and "Finish Later" actions land on the expected
 * destination with no blank or re-entering intermediate screens.
 *
 * This is a pure-logic simulation of Jetpack Compose Navigation's stack
 * semantics. It does NOT exercise the live NavController — that would
 * require instrumentation tests. Instead we encode the navigate() /
 * popBackStack() calls used by [AppNavigation] so that any future change
 * to those calls must keep these tests passing.
 *
 * Tracks issue #62: back from IntegrationEnabled lands on a blank page.
 */
class BackStackFlowTest {

    /**
     * Simulates a Jetpack Compose Navigation back stack with the ops
     * actually used by [AppNavigation].
     */
    private class FakeBackStack(initial: String) {
        private val stack: ArrayDeque<String> = ArrayDeque<String>().apply {
            addLast(initial)
        }

        val current: String get() = stack.last()
        val asList: List<String> get() = stack.toList()

        /**
         * Mirrors `navController.navigate(route) { popUpTo(popTarget) {
         * inclusive } }`. If [popTarget] is not found on the stack, no
         * entries are popped (matches the real behaviour we observed).
         */
        fun navigate(
            route: String,
            popTarget: String? = null,
            inclusive: Boolean = false,
            launchSingleTop: Boolean = false
        ) {
            if (popTarget != null) {
                val idx = stack.indexOfLast { it == popTarget }
                if (idx >= 0) {
                    val keep = if (inclusive) idx else idx + 1
                    while (stack.size > keep) {
                        stack.removeLast()
                    }
                }
            }
            if (launchSingleTop && stack.lastOrNull() == route) return
            stack.addLast(route)
        }

        /** Pops the top of the stack. Returns true if popped. */
        fun popBackStack(): Boolean {
            if (stack.size <= 1) return false
            stack.removeLast()
            return true
        }

        /**
         * Mirrors `navController.popBackStack(route, inclusive)`. Pops
         * entries until [route] is at the top (or removed, if inclusive).
         * Returns true if anything was popped.
         */
        fun popUpTo(route: String, inclusive: Boolean): Boolean {
            val idx = stack.indexOfLast { it == route }
            if (idx < 0) return false
            val keep = if (inclusive) idx else idx + 1
            if (stack.size <= keep) return false
            while (stack.size > keep) {
                stack.removeLast()
            }
            return true
        }
    }

    // ---------- Onboarding ----------

    /**
     * When the user was not previously onboarded, ONBOARDING is the start
     * destination. After onboarding completes, navigating to HOME must pop
     * ONBOARDING so back from HOME exits the app rather than re-entering
     * onboarding.
     */
    @Test
    fun `onboarding complete pops onboarding and back from home exits`() {
        val stack = FakeBackStack(Routes.ONBOARDING)

        // Mirrors AppNavigation.OnboardingState.Onboarded LaunchedEffect
        stack.navigate(
            route = Routes.HOME,
            popTarget = Routes.ONBOARDING,
            inclusive = true,
            launchSingleTop = true
        )

        assertEquals(listOf(Routes.HOME), stack.asList)
        // Back from HOME should exit (popBackStack returns false on single-entry stack)
        assertEquals(false, stack.popBackStack())
    }

    /**
     * First-time onboarding flow with #69 global notification preferences:
     * Welcome -> NotificationPreferences -> Onboarding(autostart) -> Home.
     *
     * #392: NotificationPreferences "Continue" no longer navigates to HOME
     * directly. It navigates back to ONBOARDING with `autostart=true` and
     * pops the prior onboarding entry inclusive so the destination's VM
     * (the one that shows progress + eventually flips to Home) is the same
     * VM that kicks off registration. After the flow succeeds the
     * onboarding destination LaunchedEffect navigates to HOME, again
     * popping ONBOARDING inclusive.
     */
    @Test
    fun `onboarding visits notification preferences before home`() {
        // NavHost stores each entry by its registered route pattern. The
        // concrete autostart arg lives in the entry's Bundle, not in the
        // pattern, so both the Welcome entry and the autostart entry live
        // under the [Routes.ONBOARDING] pattern.
        val stack = FakeBackStack(Routes.ONBOARDING)

        // Welcome "Get Started" -> NotificationPreferences (plain navigate)
        stack.navigate(Routes.NOTIFICATION_PREFERENCES)
        assertEquals(
            listOf(Routes.ONBOARDING, Routes.NOTIFICATION_PREFERENCES),
            stack.asList
        )

        // #392: NotificationPreferences "Continue" -> autostart route,
        // popping the prior ONBOARDING entry inclusive. The new entry
        // shares the same pattern but carries autostart=true in its args.
        stack.navigate(
            route = Routes.ONBOARDING,
            popTarget = Routes.ONBOARDING,
            inclusive = true,
            launchSingleTop = true
        )
        assertEquals(listOf(Routes.ONBOARDING), stack.asList)

        // The onboarding destination's LaunchedEffect on Onboarded -> HOME,
        // popping the onboarding entry inclusive.
        stack.navigate(
            route = Routes.HOME,
            popTarget = Routes.ONBOARDING,
            inclusive = true,
            launchSingleTop = true
        )
        assertEquals(listOf(Routes.HOME), stack.asList)
        // Back from HOME should exit the app (stack is empty beneath HOME)
        assertEquals(false, stack.popBackStack())
    }

    /**
     * #392 regression: The resolved autostart URL is distinct from the
     * registered pattern, but the constant carries the right query.
     */
    @Test
    fun `onboardingAutostart constant has autostart true query`() {
        assertEquals("onboarding?autostart=true", Routes.ONBOARDING_AUTOSTART)
    }

    /**
     * The user taps Back on NotificationPreferences — this should take
     * them back to Welcome, not out of the app.
     */
    @Test
    fun `back from notification preferences returns to welcome`() {
        val stack = FakeBackStack(Routes.ONBOARDING)
        stack.navigate(Routes.NOTIFICATION_PREFERENCES)

        assertEquals(true, stack.popBackStack())
        assertEquals(listOf(Routes.ONBOARDING), stack.asList)
    }

    /**
     * If the user was already onboarded, HOME is the start destination.
     * The LaunchedEffect still fires via launchSingleTop=true but must
     * not create a duplicate entry.
     */
    @Test
    fun `onboarded user starts on home with single entry`() {
        val stack = FakeBackStack(Routes.HOME)

        // Simulate the LaunchedEffect firing again (popUpTo ONBOARDING is a no-op)
        stack.navigate(
            route = Routes.HOME,
            popTarget = Routes.ONBOARDING,
            inclusive = true,
            launchSingleTop = true
        )

        assertEquals(listOf(Routes.HOME), stack.asList)
    }

    // ---------- IntegrationEnabled back behaviour (#62) ----------

    /**
     * Fresh Health Connect setup flow. Back from IntegrationEnabled must
     * land on IntegrationManage — not on the transient IntegrationSetup
     * entry, which would either flash blank (Idle state) or auto-navigate
     * forward again (Complete state).
     */
    @Test
    fun `health connect setup - back from enabled lands on manage`() {
        val stack = FakeBackStack(Routes.HOME)

        // HOME -> ADD_INTEGRATION
        stack.navigate(Routes.ADD_INTEGRATION)
        // ADD_INTEGRATION -> HEALTH_CONNECT_SETUP
        stack.navigate(Routes.HEALTH_CONNECT_SETUP)
        // HEALTH_CONNECT_SETUP -> INTEGRATION_SETUP (popUpTo ADD_INTEGRATION inclusive)
        stack.navigate(
            route = Routes.INTEGRATION_SETUP,
            popTarget = Routes.ADD_INTEGRATION,
            inclusive = true
        )
        // INTEGRATION_SETUP.Complete -> INTEGRATION_ENABLED (popUpTo ADD_INTEGRATION inclusive)
        stack.navigate(
            route = Routes.INTEGRATION_ENABLED,
            popTarget = Routes.ADD_INTEGRATION,
            inclusive = true
        )

        // At this point the stack contains [HOME, INTEGRATION_SETUP, INTEGRATION_ENABLED]
        // because ADD_INTEGRATION was already popped by the previous call.
        assertEquals(
            listOf(Routes.HOME, Routes.INTEGRATION_SETUP, Routes.INTEGRATION_ENABLED),
            stack.asList
        )

        // #62 fix: back performs navigate(manage) { popUpTo(HOME) } instead
        // of popBackStack().
        stack.navigate(
            route = Routes.INTEGRATION_MANAGE,
            popTarget = Routes.HOME,
            inclusive = false
        )

        assertEquals(
            listOf(Routes.HOME, Routes.INTEGRATION_MANAGE),
            stack.asList
        )
    }

    /**
     * Non-permission integration (legacy path without a dedicated
     * *_SETUP screen). Back must still land on IntegrationManage.
     */
    @Test
    fun `generic integration setup - back from enabled lands on manage`() {
        val stack = FakeBackStack(Routes.HOME)

        stack.navigate(Routes.ADD_INTEGRATION)
        stack.navigate(Routes.INTEGRATION_SETUP)
        stack.navigate(
            route = Routes.INTEGRATION_ENABLED,
            popTarget = Routes.ADD_INTEGRATION,
            inclusive = true
        )

        // [HOME, INTEGRATION_ENABLED]
        assertEquals(
            listOf(Routes.HOME, Routes.INTEGRATION_ENABLED),
            stack.asList
        )

        stack.navigate(
            route = Routes.INTEGRATION_MANAGE,
            popTarget = Routes.HOME,
            inclusive = false
        )

        assertEquals(
            listOf(Routes.HOME, Routes.INTEGRATION_MANAGE),
            stack.asList
        )
    }

    /**
     * Add Client flow: user is on IntegrationManage for an existing
     * integration and taps "Add Client", which navigates to
     * IntegrationEnabled. Back must land on the same IntegrationManage.
     */
    @Test
    fun `add client - back from enabled lands on manage`() {
        val stack = FakeBackStack(Routes.HOME)

        stack.navigate(Routes.INTEGRATION_MANAGE)
        stack.navigate(Routes.INTEGRATION_ENABLED)

        // #62 fix back handler
        stack.navigate(
            route = Routes.INTEGRATION_MANAGE,
            popTarget = Routes.HOME,
            inclusive = false
        )

        assertEquals(
            listOf(Routes.HOME, Routes.INTEGRATION_MANAGE),
            stack.asList
        )
    }

    // ---------- IntegrationSetup terminal-state cancel (#76) ----------

    /**
     * When setup reaches the Failed state (cert provisioning failed before
     * registration), cancel must land on HOME — the integration is not
     * registered, so IntegrationManage would render a non-existent
     * integration.
     */
    @Test
    fun `setup failed - cancel lands on home not manage`() {
        val stack = FakeBackStack(Routes.HOME)

        stack.navigate(Routes.ADD_INTEGRATION)
        stack.navigate(
            route = Routes.INTEGRATION_SETUP,
            popTarget = Routes.ADD_INTEGRATION,
            inclusive = true
        )

        // #76 fix: cancel from Failed performs
        // popBackStack(HOME, inclusive=false) rather than navigating to
        // IntegrationManage for a potentially-unregistered integration.
        stack.popUpTo(Routes.HOME, inclusive = false)

        assertEquals(listOf(Routes.HOME), stack.asList)
    }

    /**
     * Same as above but for the RateLimited terminal state.
     */
    @Test
    fun `setup rate limited - cancel lands on home not manage`() {
        val stack = FakeBackStack(Routes.HOME)

        stack.navigate(Routes.ADD_INTEGRATION)
        stack.navigate(
            route = Routes.INTEGRATION_SETUP,
            popTarget = Routes.ADD_INTEGRATION,
            inclusive = true
        )

        stack.popUpTo(Routes.HOME, inclusive = false)

        assertEquals(listOf(Routes.HOME), stack.asList)
    }

    /**
     * Locks in the pre-fix behaviour for Provisioning: cancel while the
     * flow is still in progress continues to navigate to
     * IntegrationManage (per #76 we only change the terminal
     * Failed/RateLimited paths).
     */
    @Test
    fun `setup provisioning - cancel still navigates to manage`() {
        val stack = FakeBackStack(Routes.HOME)

        stack.navigate(Routes.ADD_INTEGRATION)
        stack.navigate(
            route = Routes.INTEGRATION_SETUP,
            popTarget = Routes.ADD_INTEGRATION,
            inclusive = true
        )

        // Current Provisioning cancel handler
        stack.navigate(
            route = Routes.INTEGRATION_MANAGE,
            popTarget = Routes.HOME,
            inclusive = false
        )

        assertEquals(
            listOf(Routes.HOME, Routes.INTEGRATION_MANAGE),
            stack.asList
        )
    }

    // ---------- IntegrationManage back behaviour ----------

    /**
     * From Home -> IntegrationManage, back lands on Home.
     */
    @Test
    fun `manage - back from home navigation lands on home`() {
        val stack = FakeBackStack(Routes.HOME)
        stack.navigate(Routes.INTEGRATION_MANAGE)

        stack.popBackStack()

        assertEquals(listOf(Routes.HOME), stack.asList)
    }

    // ---------- Integration Settings back ----------

    /**
     * Manage -> Health Connect Settings -> back lands on Manage.
     */
    @Test
    fun `settings - back from health connect settings lands on manage`() {
        val stack = FakeBackStack(Routes.HOME)
        stack.navigate(Routes.INTEGRATION_MANAGE)
        stack.navigate(Routes.HEALTH_CONNECT_SETUP)

        stack.popBackStack()

        assertEquals(
            listOf(Routes.HOME, Routes.INTEGRATION_MANAGE),
            stack.asList
        )
    }

    // ---------- AuthApproval back ----------

    /**
     * When AuthApproval is opened while an IntegrationEnabled screen is
     * live, back returns to the live session screen.
     */
    @Test
    fun `auth approval - back during live session returns to enabled`() {
        val stack = FakeBackStack(Routes.HOME)
        stack.navigate(Routes.INTEGRATION_ENABLED)
        stack.navigate(Routes.AUTH_APPROVAL)

        stack.popBackStack()

        assertEquals(
            listOf(Routes.HOME, Routes.INTEGRATION_ENABLED),
            stack.asList
        )
    }

    /**
     * When AuthApproval is opened standalone (no prior enabled screen)
     * back lands on Home.
     */
    @Test
    fun `auth approval - standalone back lands on home`() {
        val stack = FakeBackStack(Routes.HOME)
        stack.navigate(Routes.AUTH_APPROVAL)

        stack.popBackStack()

        assertEquals(listOf(Routes.HOME), stack.asList)
    }

    /**
     * When the user approves the last pending request, AuthApproval
     * auto-navigates to IntegrationManage with popUpTo(HOME).
     */
    @Test
    fun `auth approval - approve last request lands on manage`() {
        val stack = FakeBackStack(Routes.HOME)
        stack.navigate(Routes.INTEGRATION_ENABLED)
        stack.navigate(Routes.AUTH_APPROVAL)

        // approval complete -> navigate(manage) { popUpTo(HOME) }
        stack.navigate(
            route = Routes.INTEGRATION_MANAGE,
            popTarget = Routes.HOME,
            inclusive = false
        )

        assertEquals(
            listOf(Routes.HOME, Routes.INTEGRATION_MANAGE),
            stack.asList
        )
    }
}
