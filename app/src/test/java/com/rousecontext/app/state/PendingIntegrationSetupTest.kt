package com.rousecontext.app.state

import com.rousecontext.app.delivery.DeliveryActivation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pending-integration carry + the redirect decision behind #474
 * (Option A: redirect a not-yet-registered foss device to the Background
 * delivery picker when it taps "Add integration", then auto-resume into the
 * integration it was adding once a distributor is chosen).
 */
class PendingIntegrationSetupTest {

    @Test
    fun `remember then consume returns the id exactly once`() {
        val pending = PendingIntegrationSetup()

        pending.remember("health")
        assertEquals("health", pending.pendingId.value)

        // Auto-resume consumes the carried id and clears it so a later picker
        // visit (onboarding/Settings) does not re-trigger the resume.
        assertEquals("health", pending.consume())
        assertNull("consume must clear the pending id", pending.consume())
        assertNull(pending.pendingId.value)
    }

    @Test
    fun `clear drops a remembered id without returning it`() {
        val pending = PendingIntegrationSetup()

        pending.remember("notifications")
        pending.clear()

        assertNull(pending.pendingId.value)
        assertNull(pending.consume())
    }

    @Test
    fun `remember overwrites a previously pending id`() {
        val pending = PendingIntegrationSetup()

        pending.remember("health")
        pending.remember("usage")

        assertEquals("usage", pending.consume())
    }

    @Test
    fun `redirect fires only for the foss not-yet-registered delivery state`() {
        // NeedsSetup == foss device that skipped Background delivery: redirect.
        assertTrue(deliveryNeedsSetupBeforeIntegration(DeliveryActivation.NeedsSetup))
        // Active == foss registered; NotApplicable == google/FCM. Neither redirects.
        assertFalse(deliveryNeedsSetupBeforeIntegration(DeliveryActivation.Active))
        assertFalse(deliveryNeedsSetupBeforeIntegration(DeliveryActivation.NotApplicable))
    }
}
