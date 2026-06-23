package com.rousecontext.app.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.app.state.AppStatePreferences
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies [IgnoreDailyTimeLimitState] mirrors the persisted flag into a
 * synchronously-readable cache (so the FGS-type decision needs no suspension).
 */
@RunWith(RobolectricTestRunner::class)
class IgnoreDailyTimeLimitStateTest {

    private lateinit var prefs: AppStatePreferences

    @Before
    fun setUp() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        prefs = AppStatePreferences(context)
        prefs.reset()
    }

    @Test
    fun `defaults to false and tracks the persisted flag`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val state = IgnoreDailyTimeLimitState(prefs, scope)
            assertFalse("starts at the OFF default", state.isEnabled())

            prefs.setIgnoreDailyTimeLimit(true)
            withTimeout(5.seconds) {
                while (!state.isEnabled()) delay(10)
            }
            assertTrue("reflects the persisted ON value", state.isEnabled())
        } finally {
            scope.cancel()
        }
    }
}
