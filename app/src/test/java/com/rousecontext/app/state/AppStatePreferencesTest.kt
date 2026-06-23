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

    @Test
    fun `idle timeout minutes defaults to constant`() = runTest {
        assertEquals(AppStatePreferences.DEFAULT_IDLE_TIMEOUT_MINUTES, prefs.idleTimeoutMinutes())
    }

    @Test
    fun `setIdleTimeoutMinutes persists value`() = runTest {
        prefs.setIdleTimeoutMinutes(10)
        assertEquals(10, prefs.idleTimeoutMinutes())
    }

    @Test
    fun `observeIdleTimeoutMinutes emits updates`() = runTest {
        prefs.observeIdleTimeoutMinutes().test {
            assertEquals(AppStatePreferences.DEFAULT_IDLE_TIMEOUT_MINUTES, awaitItem())
            prefs.setIdleTimeoutMinutes(2)
            assertEquals(2, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `idle timeout disabled defaults to false`() = runTest {
        assertFalse(prefs.idleTimeoutDisabled())
    }

    @Test
    fun `setIdleTimeoutDisabled persists value`() = runTest {
        prefs.setIdleTimeoutDisabled(true)
        assertTrue(prefs.idleTimeoutDisabled())
    }

    @Test
    fun `observeIdleTimeoutDisabled emits updates`() = runTest {
        prefs.observeIdleTimeoutDisabled().test {
            assertFalse(awaitItem())
            prefs.setIdleTimeoutDisabled(true)
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `quick disconnect seconds defaults to constant`() = runTest {
        assertEquals(
            AppStatePreferences.DEFAULT_QUICK_DISCONNECT_SECONDS,
            prefs.quickDisconnectSeconds()
        )
    }

    @Test
    fun `setQuickDisconnectSeconds persists value`() = runTest {
        prefs.setQuickDisconnectSeconds(15)
        assertEquals(15, prefs.quickDisconnectSeconds())
    }

    @Test
    fun `observeQuickDisconnectSeconds emits updates`() = runTest {
        prefs.observeQuickDisconnectSeconds().test {
            assertEquals(AppStatePreferences.DEFAULT_QUICK_DISCONNECT_SECONDS, awaitItem())
            prefs.setQuickDisconnectSeconds(60)
            assertEquals(60, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
