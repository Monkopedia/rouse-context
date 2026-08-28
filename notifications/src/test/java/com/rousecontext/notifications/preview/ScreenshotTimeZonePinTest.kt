package com.rousecontext.notifications.preview

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `:notifications` half of the test-JVM timezone pin guard (issue #633).
 *
 * This module has its own Roborazzi suite (`NotificationScreenshotTest`) and its
 * own `build.gradle.kts`, and its fixtures render a fixed epoch millis value.
 * None of its goldens are zone-sensitive today — recording under `UTC`,
 * `America/New_York`, `Asia/Kolkata` and `Pacific/Auckland` produces identical
 * bytes — but that is a property of the current fixtures, not something the
 * module guarantees. The pin lives once in the root `build.gradle.kts` and
 * applies to every module's test tasks; this asserts it actually reaches this
 * one.
 *
 * See the `:app` copy for the full rationale.
 */
class ScreenshotTimeZonePinTest {

    @Test
    fun `test JVM default timezone is pinned to UTC`() {
        assertEquals(
            "Test JVM default timezone is not UTC. The `user.timezone` pin in the root " +
                "build.gradle.kts (issue #633) is missing or overridden, so screenshot " +
                "goldens recorded here will not match goldens recorded elsewhere.",
            "UTC",
            TimeZone.getDefault().id
        )
    }
}
