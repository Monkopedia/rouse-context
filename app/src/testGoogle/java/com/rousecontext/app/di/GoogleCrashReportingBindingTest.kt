package com.rousecontext.app.di

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.robolectric.RobolectricTestRunner

/**
 * Issue #546 deliberately left the google distribution on #233's behaviour:
 * Crashlytics collects in release builds and there is no consent UI here.
 * Flipping this flag true without also shipping that UI would disable
 * Crashlytics with no way for a user to turn it back on, so the value is
 * pinned rather than left to be changed by accident.
 */
@RunWith(RobolectricTestRunner::class)
class GoogleCrashReportingBindingTest {

    @After
    fun tearDown() = stopKoin()

    @Test
    fun `google ships no consent gate`() {
        val koin = startKoin { modules(distributionModule) }.koin

        assertFalse(koin.get<Boolean>(named("crashReportingRequiresOptIn")))
    }
}
