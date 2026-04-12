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
