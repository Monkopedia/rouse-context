package com.rousecontext.app

import android.content.Intent
import com.rousecontext.app.ui.navigation.Routes
import com.rousecontext.notifications.PerToolCallNotifier
import com.rousecontext.notifications.SessionSummaryNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression tests for [MainActivity.destinationForNotificationIntent] — the
 * notification-tap deep-link router. These tests pin the contract between the
 * notification intents emitted by [PerToolCallNotifier] /
 * [SessionSummaryNotifier] and the nav route used to pick the audit-history
 * destination's start entry.
 *
 * Fix #368 (Bug B): the previous implementation dropped every intent extra,
 * returning bare [Routes.AUDIT_BASE]. These tests encode the per-call
 * scroll-to target and the summary session window into the route so the
 * destination's nav args can deliver them to the ViewModel.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityNotificationRoutingTest {

    @Test
    fun `per-tool-call tap with scroll-to-call extra encodes it in the audit route`() {
        val intent = Intent().apply {
            action = PerToolCallNotifier.ACTION_OPEN_AUDIT_HISTORY
            putExtra(PerToolCallNotifier.EXTRA_SCROLL_TO_CALL_ID, 7042L)
        }
        val route = MainActivity.destinationForNotificationIntent(intent)
        requireNotNull(route) { "expected non-null route" }
        assertTrue(
            "expected scrollToCallId in route, got '$route'",
            route.contains("scrollToCallId=7042")
        )
    }

    @Test
    fun `session summary tap with time window encodes start and end in the audit route`() {
        val startMillis = 1_700_000_000_000L
        val endMillis = 1_700_000_123_000L
        val intent = Intent().apply {
            action = SessionSummaryNotifier.ACTION_OPEN_AUDIT_HISTORY
            putExtra(SessionSummaryNotifier.EXTRA_START_MILLIS, startMillis)
            putExtra(SessionSummaryNotifier.EXTRA_END_MILLIS, endMillis)
        }
        val route = MainActivity.destinationForNotificationIntent(intent)
        requireNotNull(route) { "expected non-null route" }
        assertTrue(
            "expected startMillis in route, got '$route'",
            route.contains("startMillis=$startMillis")
        )
        assertTrue(
            "expected endMillis in route, got '$route'",
            route.contains("endMillis=$endMillis")
        )
    }

    @Test
    fun `audit tap with no extras returns the bare audit route`() {
        val intent = Intent().apply {
            action = PerToolCallNotifier.ACTION_OPEN_AUDIT_HISTORY
        }
        val route = MainActivity.destinationForNotificationIntent(intent)
        assertEquals(Routes.AUDIT_BASE, route)
    }

    @Test
    fun `summary tap with no extras returns the bare audit route`() {
        val intent = Intent().apply {
            action = SessionSummaryNotifier.ACTION_OPEN_AUDIT_HISTORY
        }
        val route = MainActivity.destinationForNotificationIntent(intent)
        assertEquals(Routes.AUDIT_BASE, route)
    }

    @Test
    fun `manage action with an integration id routes to integration manage`() {
        val intent = Intent().apply {
            action = PerToolCallNotifier.ACTION_OPEN_INTEGRATION_MANAGE
            putExtra(PerToolCallNotifier.EXTRA_INTEGRATION_ID, "health")
        }
        val route = MainActivity.destinationForNotificationIntent(intent)
        assertEquals(Routes.integrationManage("health"), route)
    }

    @Test
    fun `manage action with no integration id falls back to home`() {
        val intent = Intent().apply {
            action = PerToolCallNotifier.ACTION_OPEN_INTEGRATION_MANAGE
        }
        val route = MainActivity.destinationForNotificationIntent(intent)
        assertEquals(Routes.HOME, route)
    }

    @Test
    fun `open-home action returns the home route`() {
        val intent = Intent().apply {
            action = SessionSummaryNotifier.ACTION_OPEN_HOME
        }
        val route = MainActivity.destinationForNotificationIntent(intent)
        assertEquals(Routes.HOME, route)
    }

    @Test
    fun `unrelated intent returns null so launcher taps are unaffected`() {
        val intent = Intent().apply { action = Intent.ACTION_MAIN }
        assertNull(MainActivity.destinationForNotificationIntent(intent))
    }

    @Test
    fun `null intent returns null`() {
        assertNull(MainActivity.destinationForNotificationIntent(null))
    }
}
