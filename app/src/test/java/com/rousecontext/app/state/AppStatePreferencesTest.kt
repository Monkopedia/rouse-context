package com.rousecontext.app.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppStatePreferencesTest {

    private lateinit var prefs: AppStatePreferences

    @Before
    fun setUp() = kotlinx.coroutines.runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        prefs = AppStatePreferences(context)
        prefs.reset()
    }

    @Test
    fun `defaults`() = runTest {
        assertEquals(AppStatePreferences.DEFAULT_INTERVAL_HOURS, prefs.securityCheckIntervalHours())
        assertFalse(prefs.hasLaunchedBefore())
    }

    @Test
    fun `setSecurityCheckIntervalHours persists value`() = runTest {
        prefs.setSecurityCheckIntervalHours(24)
        assertEquals(24, prefs.securityCheckIntervalHours())
    }

    @Test
    fun `markLaunched flips flag`() = runTest {
        assertFalse(prefs.hasLaunchedBefore())
        prefs.markLaunched()
        assertTrue(prefs.hasLaunchedBefore())
    }

    @Test
    fun `observeSecurityCheckIntervalHours emits updates`() = runTest {
        prefs.observeSecurityCheckIntervalHours().test {
            assertEquals(AppStatePreferences.DEFAULT_INTERVAL_HOURS, awaitItem())
            prefs.setSecurityCheckIntervalHours(48)
            assertEquals(48, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
