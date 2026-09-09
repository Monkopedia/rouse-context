package com.rousecontext.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Never` is how a user stops the security check from querying public CT logs
 * with their device hostname. The encoding therefore has one hard requirement:
 * **a stored "never" must never decode back into a live interval.** That
 * failure would silently resume the network egress the user switched off, which
 * is the exact harm the option exists to prevent.
 *
 * [SecurityCheckIntervalOption.forHours] snaps any unrecognised hour count to
 * `HOURS_12`, so an encoding that put "never" in the interval-hours preference
 * would be one stale read away from that. `Never` is stored as its own boolean
 * instead ([com.rousecontext.work.SecurityCheckPreferences.securityCheckEnabled]),
 * and `NEVER.hours` is null so no integer can name it.
 */
class SecurityCheckIntervalOptionTest {

    @Test
    fun `no hour count decodes to NEVER`() {
        // The load-bearing assertion. Covers the whole int range a preference
        // could plausibly hold — legacy values, zero, negatives, garbage.
        val decoded = (-500..500).map { SecurityCheckIntervalOption.forHours(it) }

        assertTrue(
            "forHours() must never produce NEVER: an hours value that decoded to " +
                "Never would let a corrupt or legacy interval silently disable the " +
                "checks, and the reverse mapping would then re-enable them",
            decoded.none { it == SecurityCheckIntervalOption.NEVER }
        )
    }

    @Test
    fun `NEVER has no hours value that could be persisted into the interval key`() {
        assertNull(SecurityCheckIntervalOption.NEVER.hours)
    }

    @Test
    fun `never round-trips as never even when a live interval is still stored`() {
        // The pre-existing-install case: the user has been on 6-hourly checks
        // for months, then picks Never. The stored hour count stays 6 (so
        // switching back restores their cadence) and must not win.
        assertEquals(
            SecurityCheckIntervalOption.NEVER,
            SecurityCheckIntervalOption.from(enabled = false, hours = 6)
        )
        assertEquals(
            SecurityCheckIntervalOption.NEVER,
            SecurityCheckIntervalOption.from(enabled = false, hours = 12)
        )
        assertEquals(
            SecurityCheckIntervalOption.NEVER,
            SecurityCheckIntervalOption.from(enabled = false, hours = 24)
        )
    }

    @Test
    fun `an enabled install decodes to its stored interval`() {
        // Control direction: without this, an implementation hardwired to NEVER
        // would pass every assertion above.
        assertEquals(
            SecurityCheckIntervalOption.HOURS_6,
            SecurityCheckIntervalOption.from(enabled = true, hours = 6)
        )
        assertEquals(
            SecurityCheckIntervalOption.HOURS_24,
            SecurityCheckIntervalOption.from(enabled = true, hours = 24)
        )
    }

    @Test
    fun `a fresh install with no stored flag decodes to the default interval`() {
        // DEFAULT_SECURITY_CHECK_ENABLED is true, so nothing becomes opt-in.
        assertEquals(
            SecurityCheckIntervalOption.HOURS_12,
            SecurityCheckIntervalOption.from(
                enabled = com.rousecontext.work.SecurityCheckPreferences
                    .DEFAULT_SECURITY_CHECK_ENABLED,
                hours = 12
            )
        )
    }

    @Test
    fun `an unrecognised stored interval still snaps to the 12-hour default`() {
        // Pre-existing behaviour, pinned so the NEVER addition doesn't change it.
        assertEquals(
            SecurityCheckIntervalOption.HOURS_12,
            SecurityCheckIntervalOption.forHours(99)
        )
    }
}
