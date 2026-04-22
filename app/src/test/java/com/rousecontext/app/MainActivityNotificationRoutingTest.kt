package com.rousecontext.app

import android.content.Intent
import com.rousecontext.app.ui.navigation.Routes
import com.rousecontext.notifications.PerToolCallNotifier
import com.rousecontext.notifications.SessionSummaryNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `session summary tap returns bare audit route with no session window`() {
        // #370: summary taps no longer carry EXTRA_START_MILLIS /
        // EXTRA_END_MILLIS — the #347 session-window override was reverted
        // because it silently overrode the date-chip selection and made the
        // audit filter look broken. Summary taps now land at the default
        // LAST_7_DAYS filter with no scroll, which always encloses the
        // dashboard's rolling-24h teaser window.
        val intent = Intent().apply {
            action = SessionSummaryNotifier.ACTION_OPEN_AUDIT_HISTORY
        }
        val route = MainActivity.destinationForNotificationIntent(intent)
        requireNotNull(route) { "expected non-null route" }
        assertFalse(
            "summary tap route must not encode startMillis, got '$route'",
            route.contains("startMillis")
        )
        assertFalse(
            "summary tap route must not encode endMillis, got '$route'",
            route.contains("endMillis")
        )
        assertEquals(Routes.AUDIT_BASE, route)
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
