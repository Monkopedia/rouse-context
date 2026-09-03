package com.rousecontext.app.ui.screenshots

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the repo-wide test-JVM timezone pin declared in the root
 * `build.gradle.kts` (issue #633).
 *
 * Several *display* formatters read the JVM default zone on purpose. Since
 * #635 they all route through `DisplayDateFormat`, which resolves
 * `ZoneId.systemDefault()` and `Locale.getDefault()` on **every call** — the
 * audit-detail timestamp, the audit-history row time and day grouping, and the
 * `MMM d` / `MMM d, yyyy` dates in the onboarding and integration view models.
 * (Before #635 these were `SimpleDateFormat` statics that captured the zone
 * once at class init. That is why the pin is a JVM system property rather than
 * a `@Before` rule; see the root `build.gradle.kts`. The capture is gone, the
 * pin is not redundant.)
 *
 * Device-local time is the right product behaviour, so the determinism has to
 * come from the test JVM — and resolving per call makes the pin *more* load
 * bearing, not less, because there is no longer any class-init ordering that
 * could accidentally freeze a zone before a test changed it.
 *
 * Without the pin the goldens are deterministic per host but different across
 * hosts, so `recordRoborazziDebug` on a non-UTC machine looks clean locally and
 * fails `verifyRoborazziDebug` on a UTC CI runner — the failure mode that took
 * three investigations to identify. Only the audit-detail timestamp reaches a
 * golden today — but it reaches three of them:
 *
 *  - `app/screenshots/43_audit_detail_populated_dark.png`
 *  - `app/screenshots/43_audit_detail_populated_light.png`
 *    (both from `ScreenScreenshotTest.auditDetailPopulated{Dark,Light}`)
 *  - `fastlane/metadata/android/en-US/images/phoneScreenshots/6_audit_detail.png`
 *    (from `ListingScreenshotTest.auditDetail`, which renders its own copy of
 *    the fixture into the fastlane store-listing metadata)
 *
 * The rest are latent and would surface the same way the moment a fixture or a
 * screen starts rendering them. Asserting the JVM default zone covers all of
 * them at once, and turns "somebody deleted the pin" into an immediate local
 * failure instead of a mystery red board later.
 *
 * That list is measured, not inferred. To re-measure it, comment out
 * `systemProperty("user.timezone", "UTC")` in the root `build.gradle.kts` and
 * run `TZ=America/New_York ./gradlew :app:verifyRoborazziDebug --rerun-tasks`.
 * `--rerun-tasks` is load bearing: `TZ` is not a Gradle task input, so without
 * it the task goes `UP-TO-DATE` and reports a green that measured nothing.
 * Count the failing *tests*, not the exit code, and ignore `_compare.png` files
 * in `app/build/outputs/roborazzi/` — those are diff artifacts, not goldens.
 * Every earlier version of this list was derived by reasoning about which
 * screens plausibly render a timestamp; each one enumerated two goldens and
 * missed `6_audit_detail`, and the error was inherited unchecked across three
 * investigations before anyone removed the pin and read what actually failed
 * (#719).
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
