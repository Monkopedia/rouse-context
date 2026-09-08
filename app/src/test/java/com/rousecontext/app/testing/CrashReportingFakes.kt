package com.rousecontext.app.testing

import com.rousecontext.api.CrashReporter
import com.rousecontext.app.state.AppStatePreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * [CrashReporter] that records what collection state it was told to apply, so
 * a test can assert the concrete value instead of "was called".
 */
class RecordingCrashReporter : CrashReporter {
    val collectionEnabledCalls = mutableListOf<Boolean>()

    override fun logCaughtException(throwable: Throwable) = Unit

    override fun log(message: String) = Unit

    override fun setCollectionEnabled(enabled: Boolean) {
        collectionEnabledCalls += enabled
    }
}

/**
 * In-memory stand-in for the crash-reporting keys of [AppStatePreferences].
 *
 * ViewModel tests write through `viewModelScope.launch`, and
 * `advanceUntilIdle()` only drains the test scheduler — it does not wait for
 * the real DataStore's own async write, so a test asserting straight after it
 * reads a stale value. Persistence across a real process is proved against the
 * genuine DataStore in `CrashReportingPreferenceTest`; here the store just has
 * to answer synchronously.
 */
fun inMemoryCrashReportingPreferences(
    optIn: Boolean = false,
    consentAsked: Boolean = false
): AppStatePreferences {
    val optInState = MutableStateFlow(optIn)
    var asked = consentAsked
    return mockk(relaxed = true) {
        coEvery { crashReportingOptIn() } answers { optInState.value }
        coEvery { setCrashReportingOptIn(any()) } answers { optInState.value = firstArg() }
        every { observeCrashReportingOptIn() } returns optInState
        coEvery { crashReportingConsentAsked() } answers { asked }
        coEvery { markCrashReportingConsentAsked() } answers { asked = true }
    }
}
