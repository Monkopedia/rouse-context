package com.rousecontext.app.ui.viewmodels

import com.rousecontext.app.state.AppStatePreferences
import com.rousecontext.app.support.CrashReportingPreference
import com.rousecontext.app.testing.MainDispatcherRule
import com.rousecontext.app.testing.RecordingCrashReporter
import com.rousecontext.app.testing.inMemoryCrashReportingPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Issue #546: the first-run consent sheet is shown exactly once, by any exit,
 * and dismissing it leaves crash reporting off.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrashReportConsentViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    private val reporter = RecordingCrashReporter()
    private val preferences = inMemoryCrashReportingPreferences()

    @Test
    fun `sheet is shown on a fresh install`() = runTest(testDispatcher) {
        val vm = CrashReportConsentViewModel(fossPreference())
        advanceUntilIdle()

        assertTrue(vm.visible.value)
    }

    @Test
    fun `turn on enables collection and persists it`() = runTest(testDispatcher) {
        val vm = CrashReportConsentViewModel(fossPreference())
        advanceUntilIdle()

        vm.turnOn()
        advanceUntilIdle()

        assertFalse(vm.visible.value)
        assertEquals(listOf(true), reporter.collectionEnabledCalls)
        assertTrue(preferences.crashReportingOptIn())
    }

    @Test
    fun `not now leaves collection off`() = runTest(testDispatcher) {
        val vm = CrashReportConsentViewModel(fossPreference())
        advanceUntilIdle()

        vm.dismiss()
        advanceUntilIdle()

        assertFalse(vm.visible.value)
        assertEquals(emptyList<Boolean>(), reporter.collectionEnabledCalls)
        assertFalse(preferences.crashReportingOptIn())
    }

    @Test
    fun `not now still marks the question as asked`() = runTest(testDispatcher) {
        val vm = CrashReportConsentViewModel(fossPreference())
        advanceUntilIdle()

        vm.dismiss()
        advanceUntilIdle()

        assertTrue(preferences.crashReportingConsentAsked())
    }

    @Test
    fun `turn on also marks the question as asked`() = runTest(testDispatcher) {
        val vm = CrashReportConsentViewModel(fossPreference())
        advanceUntilIdle()

        vm.turnOn()
        advanceUntilIdle()

        assertTrue(preferences.crashReportingConsentAsked())
    }

    @Test
    fun `sheet does not return after turn on`() = runTest(testDispatcher) {
        val first = CrashReportConsentViewModel(fossPreference())
        advanceUntilIdle()
        first.turnOn()
        advanceUntilIdle()

        val second = CrashReportConsentViewModel(fossPreference())
        advanceUntilIdle()

        assertFalse(second.visible.value)
    }

    @Test
    fun `sheet does not return after a dismissal`() = runTest(testDispatcher) {
        val first = CrashReportConsentViewModel(fossPreference())
        advanceUntilIdle()
        first.dismiss()
        advanceUntilIdle()

        val second = CrashReportConsentViewModel(fossPreference())
        advanceUntilIdle()

        assertFalse(second.visible.value)
    }

    /**
     * A dismissal is a final answer, and the answer is "no": the next launch
     * re-affirms collection from the stored preference and gets `false`.
     */
    @Test
    fun `a dismissed sheet leaves collection off across a relaunch`() = runTest(testDispatcher) {
        val vm = CrashReportConsentViewModel(fossPreference())
        advanceUntilIdle()
        vm.dismiss()
        advanceUntilIdle()

        val afterRelaunch = RecordingCrashReporter()
        fossPreference(afterRelaunch).applyToReporter()

        assertEquals(listOf(false), afterRelaunch.collectionEnabledCalls)
    }

    @Test
    fun `an accepted sheet keeps collection on across a relaunch`() = runTest(testDispatcher) {
        val vm = CrashReportConsentViewModel(fossPreference())
        advanceUntilIdle()
        vm.turnOn()
        advanceUntilIdle()

        val afterRelaunch = RecordingCrashReporter()
        fossPreference(afterRelaunch).applyToReporter()

        assertEquals(listOf(true), afterRelaunch.collectionEnabledCalls)
    }

    @Test
    fun `google never shows the sheet`() = runTest(testDispatcher) {
        val vm = CrashReportConsentViewModel(
            CrashReportingPreference(
                preferences = preferences,
                crashReporter = reporter,
                requiresOptIn = false,
                isDebugBuild = false
            )
        )
        advanceUntilIdle()

        assertFalse(vm.visible.value)
    }

    private fun fossPreference(
        crashReporter: RecordingCrashReporter = reporter,
        appStatePreferences: AppStatePreferences = preferences
    ) = CrashReportingPreference(
        preferences = appStatePreferences,
        crashReporter = crashReporter,
        requiresOptIn = true,
        isDebugBuild = false
    )
}
