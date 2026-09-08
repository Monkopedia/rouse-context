package com.rousecontext.app.di

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.robolectric.RobolectricTestRunner

/**
 * Issue #546: the whole opt-in gate hangs off one Koin flag. If the foss
 * `DistributionModule` bound it false, `CrashReportingPreference` would fall
 * back to the google rule — collect in every release build — and every other
 * test here would still pass, because they all inject the flag directly.
 *
 * `single` definitions are lazy, so resolving this one flag does not drag in
 * the rest of the module's Android dependencies.
 */
@RunWith(RobolectricTestRunner::class)
class FossCrashReportingBindingTest {

    @After
    fun tearDown() = stopKoin()

    @Test
    fun `foss requires opt-in before crash reports are collected`() {
        val koin = startKoin { modules(distributionModule) }.koin

        assertTrue(koin.get<Boolean>(named("crashReportingRequiresOptIn")))
    }
}
