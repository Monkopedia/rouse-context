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
class CertRenewalPreferencesTest {

    private lateinit var prefs: CertRenewalPreferences

    @Before
    fun setUp() = kotlinx.coroutines.runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        prefs = CertRenewalPreferences(context)
        // Reset shared DataStore state between tests.
        prefs.recordAttempt(attemptAt = 0L, outcome = "")
    }

    @Test
    fun `defaults are zero and empty`() = runTest {
        // After reset, both fields are at the sentinel values.
        assertEquals(0L, prefs.lastAttemptAt())
        assertEquals("", prefs.lastOutcome())
    }

    @Test
    fun `recordAttempt stores both values atomically`() = runTest {
        prefs.recordAttempt(attemptAt = 9_999L, outcome = "SUCCESS")
        assertEquals(9_999L, prefs.lastAttemptAt())
        assertEquals("SUCCESS", prefs.lastOutcome())
    }

    @Test
    fun `observeLastOutcome emits updates`() = runTest {
        prefs.recordAttempt(attemptAt = 1L, outcome = "SUCCESS")
        prefs.observeLastOutcome().test {
            assertEquals("SUCCESS", awaitItem())
            prefs.recordAttempt(attemptAt = 2L, outcome = "CN_MISMATCH")
            assertEquals("CN_MISMATCH", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
