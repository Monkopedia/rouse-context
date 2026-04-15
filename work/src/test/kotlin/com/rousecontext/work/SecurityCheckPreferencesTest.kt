package com.rousecontext.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SecurityCheckPreferencesTest {

    private lateinit var prefs: SecurityCheckPreferences

    @Before
    fun setUp() = kotlinx.coroutines.runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        prefs = SecurityCheckPreferences(context)
        // DataStore files persist within the JVM across tests; reset to a
        // known state before each test. This is test-only.
        prefs.clearResults()
        prefs.setCertFingerprint("")
    }

    @Test
    fun `defaults are zero and empty`() = runTest {
        assertEquals(0L, prefs.lastCheckAt())
        assertEquals("", prefs.selfCertResult())
        assertEquals("", prefs.ctLogResult())
        assertEquals("", prefs.certFingerprint())
    }

    @Test
    fun `recordCheck stores all three values atomically`() = runTest {
        prefs.clearResults()
        prefs.recordCheck(
            lastCheckAt = 1_234L,
            selfCertResult = "verified",
            ctLogResult = "warning"
        )
        assertEquals(1_234L, prefs.lastCheckAt())
        assertEquals("verified", prefs.selfCertResult())
        assertEquals("warning", prefs.ctLogResult())
    }

    @Test
    fun `setCertFingerprint is independent of recordCheck values`() = runTest {
        prefs.clearResults()
        prefs.recordCheck(1L, "verified", "verified")
        prefs.setCertFingerprint("AA:BB:CC")
        assertEquals("AA:BB:CC", prefs.certFingerprint())
        assertEquals("verified", prefs.selfCertResult())
    }

    @Test
    fun `clearResults resets time and result fields`() = runTest {
        prefs.recordCheck(1L, "alert", "alert")
        prefs.clearResults()
        assertEquals(0L, prefs.lastCheckAt())
        assertEquals("", prefs.selfCertResult())
        assertEquals("", prefs.ctLogResult())
    }

    @Test
    fun `observeLastCheckAt emits updates`() = runTest {
        prefs.clearResults()
        prefs.observeLastCheckAt().test {
            assertEquals(0L, awaitItem())
            prefs.recordCheck(42L, "verified", "verified")
            assertEquals(42L, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
