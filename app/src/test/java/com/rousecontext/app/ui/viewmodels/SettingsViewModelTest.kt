package com.rousecontext.app.ui.viewmodels

import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.rousecontext.api.CrashReporter
import com.rousecontext.api.NotificationSettings
import com.rousecontext.api.NotificationSettingsProvider
import com.rousecontext.api.PostSessionMode
import com.rousecontext.app.state.AppStatePreferences
import com.rousecontext.app.state.ThemeMode
import com.rousecontext.app.state.ThemePreference
import com.rousecontext.app.support.CrashReportingPreference
import com.rousecontext.app.testing.MainDispatcherRule
import com.rousecontext.app.testing.RecordingCrashReporter
import com.rousecontext.app.testing.inMemoryCrashReportingPreferences
import com.rousecontext.app.ui.screens.PostSessionModeOption
import com.rousecontext.app.ui.screens.SecurityCheckIntervalOption
import com.rousecontext.app.ui.screens.SettingsState
import com.rousecontext.app.ui.screens.TrustOverallStatus
import com.rousecontext.work.SecurityCheckPreferences
import com.rousecontext.work.SecurityCheckWorker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    @Test
    fun `initial state uses default settings`() = runTest(testDispatcher) {
        val vm = createViewModel(PostSessionMode.SUMMARY)
        vm.state.test {
            val state = awaitItem()
            assertEquals(5, state.idleTimeoutMinutes)
            assertEquals(PostSessionModeOption.SUMMARY, state.postSessionMode)
        }
    }

    @Test
    fun `initial state emits loading then loaded`() = runTest(testDispatcher) {
        val vm = createViewModel(PostSessionMode.SUMMARY)
        vm.state.test {
            val loading = awaitItem()
            assertTrue(loading.isLoading)
            val loaded = awaitItem()
            assertFalse(loaded.isLoading)
            assertNull(loaded.errorMessage)
        }
    }

    @Test
    fun `state reflects notification settings provider`() = runTest(testDispatcher) {
        val vm = createViewModel(PostSessionMode.EACH_USAGE)
        vm.state.test {
            awaitItem() // initial default
            val state = awaitItem()
            assertEquals(PostSessionModeOption.EACH_USAGE, state.postSessionMode)
        }
    }

    @Test
    fun `state reflects suppress mode`() = runTest(testDispatcher) {
        val vm = createViewModel(PostSessionMode.SUPPRESS)
        vm.state.test {
            awaitItem()
            val state = awaitItem()
            assertEquals(PostSessionModeOption.SUPPRESS, state.postSessionMode)
        }
    }

    @Test
    fun `trust status is null when no prefs provided`() = runTest(testDispatcher) {
        val vm = createViewModel(PostSessionMode.SUMMARY, securityPrefs = null)
        vm.state.test {
            val state = awaitItem()
            assertNull(state.trustStatus)
        }
    }

    @Test
    fun `trust status is null when no checks have run`() = runTest(testDispatcher) {
        val prefs = freshSecurityPrefs()
        val vm = createViewModel(PostSessionMode.SUMMARY, securityPrefs = prefs)
        vm.state.test {
            val state = awaitItem()
            assertNull(state.trustStatus)
        }
    }

    @Test
    fun `trust status shows verified when both checks pass`() = runTest(testDispatcher) {
        val prefs = freshSecurityPrefs()
        runBlocking {
            prefs.recordCheck(
                lastCheckAt = 1_000L,
                selfCertResult = "verified",
                ctLogResult = "verified"
            )
            prefs.setCertFingerprint("AA:BB:CC")
        }
        val vm = createViewModel(PostSessionMode.SUMMARY, securityPrefs = prefs)
        vm.state.test {
            awaitItem()
            val state = awaitItem()
            assertNotNull(state.trustStatus)
            assertEquals(TrustOverallStatus.VERIFIED, state.trustStatus?.overallStatus)
            assertEquals("verified", state.trustStatus?.selfCheckResult)
            assertEquals("verified", state.trustStatus?.ctCheckResult)
            assertEquals("AA:BB:CC", state.trustStatus?.certFingerprint)
        }
    }

    @Test
    fun `trust status shows warning when self-check warns`() = runTest(testDispatcher) {
        val prefs = freshSecurityPrefs()
        runBlocking {
            prefs.recordCheck(1_000L, "warning", "verified")
        }
        val vm = createViewModel(PostSessionMode.SUMMARY, securityPrefs = prefs)
        vm.state.test {
            awaitItem()
            val state = awaitItem()
            assertEquals(TrustOverallStatus.WARNING, state.trustStatus?.overallStatus)
        }
    }

    @Test
    fun `trust status shows alert when ct check alerts`() = runTest(testDispatcher) {
        val prefs = freshSecurityPrefs()
        runBlocking {
            prefs.recordCheck(1_000L, "verified", "alert")
        }
        val vm = createViewModel(PostSessionMode.SUMMARY, securityPrefs = prefs)
        vm.state.test {
            awaitItem()
            val state = awaitItem()
            assertEquals(TrustOverallStatus.ALERT, state.trustStatus?.overallStatus)
        }
    }

    @Test
    fun `alert takes precedence over warning`() = runTest(testDispatcher) {
        val prefs = freshSecurityPrefs()
        runBlocking {
            prefs.recordCheck(1_000L, "warning", "alert")
        }
        val vm = createViewModel(PostSessionMode.SUMMARY, securityPrefs = prefs)
        vm.state.test {
            awaitItem()
            val state = awaitItem()
            assertEquals(TrustOverallStatus.ALERT, state.trustStatus?.overallStatus)
        }
    }

    // -------------------------------------------------------------------
    // "Never" check interval (F-Droid review of fdroiddata!42096)
    // -------------------------------------------------------------------

    @Test
    fun `selecting Never cancels the scheduled work`() = runTest(testDispatcher) {
        // The setting must act on WorkManager when the user picks it, not on
        // the next app launch: work left enqueued keeps querying crt.sh with
        // the device hostname on its cadence.
        val recorder = ScheduleRecorder()
        val vm = createViewModel(
            PostSessionMode.SUMMARY,
            securityPrefs = freshSecurityPrefs(),
            appStatePrefs = freshAppStatePrefs(),
            applySchedule = recorder
        )

        vm.setSecurityCheckInterval(SecurityCheckIntervalOption.NEVER)

        assertEquals(
            listOf(SecurityCheckIntervalOption.NEVER),
            recorder.awaitCount(1)
        )
    }

    @Test
    fun `selecting Never persists and survives a new ViewModel`() = runTest(testDispatcher) {
        // Round-trip through the real DataStore. A choice that only lived in
        // memory would silently resume checks on the next process start.
        val securityPrefs = freshSecurityPrefs()
        val appStatePrefs = freshAppStatePrefs()
        val recorder = ScheduleRecorder()
        val vm = createViewModel(
            PostSessionMode.SUMMARY,
            securityPrefs = securityPrefs,
            appStatePrefs = appStatePrefs,
            applySchedule = recorder
        )

        vm.setSecurityCheckInterval(SecurityCheckIntervalOption.NEVER)
        recorder.awaitCount(1)

        val reloaded = createViewModel(
            PostSessionMode.SUMMARY,
            securityPrefs = securityPrefs,
            appStatePrefs = appStatePrefs
        )
        reloaded.state.test {
            awaitItem()
            assertEquals(
                SecurityCheckIntervalOption.NEVER,
                awaitItem().securityCheckInterval
            )
        }
    }

    @Test
    fun `Never does not overwrite the stored cadence so switching back restores it`() =
        runTest(testDispatcher) {
            val securityPrefs = freshSecurityPrefs()
            val appStatePrefs = freshAppStatePrefs()
            val recorder = ScheduleRecorder()
            val vm = createViewModel(
                PostSessionMode.SUMMARY,
                securityPrefs = securityPrefs,
                appStatePrefs = appStatePrefs,
                applySchedule = recorder
            )

            vm.setSecurityCheckInterval(SecurityCheckIntervalOption.HOURS_6)
            vm.setSecurityCheckInterval(SecurityCheckIntervalOption.NEVER)
            recorder.awaitCount(2)

            assertEquals(
                "the user's cadence must be remembered while checks are off",
                6,
                appStatePrefs.securityCheckIntervalHours()
            )
        }

    @Test
    fun `switching from Never back to an interval re-enables and re-enqueues`() =
        runTest(testDispatcher) {
            // Both directions. A one-way switch would be a worse bug than the
            // one being fixed.
            val recorder = ScheduleRecorder()
            val securityPrefs = freshSecurityPrefs()
            val vm = createViewModel(
                PostSessionMode.SUMMARY,
                securityPrefs = securityPrefs,
                appStatePrefs = freshAppStatePrefs(),
                applySchedule = recorder
            )

            vm.setSecurityCheckInterval(SecurityCheckIntervalOption.NEVER)
            vm.setSecurityCheckInterval(SecurityCheckIntervalOption.HOURS_24)

            assertEquals(
                listOf(
                    SecurityCheckIntervalOption.NEVER,
                    SecurityCheckIntervalOption.HOURS_24
                ),
                recorder.awaitCount(2)
            )
            assertTrue(securityPrefs.securityCheckEnabled())
        }

    @Test
    fun `trust card reads as disabled, not as the last result, once checks are off`() =
        runTest(testDispatcher) {
            // The stale-result trap: a prior "verified" must not keep showing a
            // green tick for checks that are no longer running.
            val prefs = freshSecurityPrefs()
            runBlocking {
                prefs.recordCheck(1_000L, "verified", "verified")
                prefs.setSecurityCheckEnabled(false)
            }
            val vm = createViewModel(PostSessionMode.SUMMARY, securityPrefs = prefs)

            vm.state.test {
                awaitItem()
                val state = awaitItem()
                assertEquals(
                    SecurityCheckWorker.RESULT_DISABLED,
                    state.trustStatus?.selfCheckResult
                )
                assertEquals(
                    SecurityCheckWorker.RESULT_DISABLED,
                    state.trustStatus?.ctCheckResult
                )
                assertEquals(
                    TrustOverallStatus.DISABLED,
                    state.trustStatus?.overallStatus
                )
            }
        }

    @Test
    fun `an unacknowledged alert keeps showing while checks are off`() = runTest(testDispatcher) {
        // The alert gate in McpSession still blocks integration requests on
        // the stored "alert". Relabelling that as "turned off" would hide
        // the reason requests are being refused.
        val prefs = freshSecurityPrefs()
        runBlocking {
            prefs.recordCheck(1_000L, "verified", "alert")
            prefs.setSecurityCheckEnabled(false)
        }
        val vm = createViewModel(PostSessionMode.SUMMARY, securityPrefs = prefs)

        vm.state.test {
            awaitItem()
            val state = awaitItem()
            assertEquals("alert", state.trustStatus?.ctCheckResult)
            assertEquals(
                TrustOverallStatus.ALERT,
                state.trustStatus?.overallStatus
            )
        }
    }

    @Test
    fun `setPostSessionMode maps each UI option to the domain value`() = runTest(testDispatcher) {
        val provider = mockk<NotificationSettingsProvider> {
            val s = NotificationSettings(
                postSessionMode = PostSessionMode.SUMMARY,
                notificationPermissionGranted = true
            )
            coEvery { settings() } returns s
            every { observeSettings() } returns flowOf(s)
            coEvery { setPostSessionMode(any()) } returns Unit
            coEvery { setShowAllMcpMessages(any()) } returns Unit
        }
        val vm = createViewModel(
            PostSessionMode.SUMMARY,
            securityPrefs = null,
            provider = provider
        )
        vm.setPostSessionMode(PostSessionModeOption.EACH_USAGE)
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify { provider.setPostSessionMode(PostSessionMode.EACH_USAGE) }

        vm.setPostSessionMode(PostSessionModeOption.SUPPRESS)
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify { provider.setPostSessionMode(PostSessionMode.SUPPRESS) }

        vm.setPostSessionMode(PostSessionModeOption.SUMMARY)
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify { provider.setPostSessionMode(PostSessionMode.SUMMARY) }
    }

    @Test
    fun `showAllMcpMessages reflects provider flow (default off)`() = runTest(testDispatcher) {
        val vm = createViewModel(PostSessionMode.SUMMARY)
        vm.showAllMcpMessages.test {
            assertFalse(awaitItem())
        }
    }

    @Test
    fun `showAllMcpMessages reflects provider flow when enabled`() = runTest(testDispatcher) {
        val flow = MutableStateFlow(
            NotificationSettings(
                postSessionMode = PostSessionMode.SUMMARY,
                notificationPermissionGranted = true,
                showAllMcpMessages = true
            )
        )
        val provider = mockk<NotificationSettingsProvider> {
            coEvery { settings() } returns flow.value
            every { observeSettings() } returns flow
            coEvery { setShowAllMcpMessages(any()) } returns Unit
            coEvery { setPostSessionMode(any()) } returns Unit
        }
        val vm = createViewModel(
            PostSessionMode.SUMMARY,
            securityPrefs = null,
            provider = provider
        )
        vm.showAllMcpMessages.test {
            // Drop initial default false
            val first = awaitItem()
            // Asserted unconditionally rather than branched on. The previous
            // form was `if (!first) assertTrue(awaitItem()) else assertTrue(first)`
            // — the else branch asserts a value it has just branched on being
            // true, so if the flow ever emitted true first the test passed
            // having verified nothing. Verified by removing the conditional and
            // re-running: green, so the branch was dead rather than hiding a
            // defect, and this form pins the documented emission order.
            assertFalse(first)
            assertTrue(awaitItem())
        }
    }

    @Test
    fun `setShowAllMcpMessages calls provider`() = runTest(testDispatcher) {
        val provider = mockk<NotificationSettingsProvider> {
            coEvery { settings() } returns NotificationSettings(
                postSessionMode = PostSessionMode.SUMMARY,
                notificationPermissionGranted = true
            )
            every { observeSettings() } returns flowOf(
                NotificationSettings(
                    postSessionMode = PostSessionMode.SUMMARY,
                    notificationPermissionGranted = true
                )
            )
            coEvery { setShowAllMcpMessages(any()) } returns Unit
            coEvery { setPostSessionMode(any()) } returns Unit
        }
        val vm = createViewModel(
            PostSessionMode.SUMMARY,
            securityPrefs = null,
            provider = provider
        )
        vm.setShowAllMcpMessages(true)
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify { provider.setShowAllMcpMessages(true) }

        vm.setShowAllMcpMessages(false)
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify { provider.setShowAllMcpMessages(false) }
    }

    @Test
    fun `spurious wake stats surface in state`() = runTest(testDispatcher) {
        val vm = createViewModel(
            PostSessionMode.SUMMARY,
            securityPrefs = null,
            provider = null,
            spuriousWakesFlow = flowOf(SpuriousWakeStats(rolling24h = 7, total = 42))
        )
        vm.state.test {
            awaitItem() // loading
            val state = awaitItem()
            assertEquals(7, state.spuriousWakesLast24h)
            assertEquals(42L, state.totalWakesLifetime)
        }
    }

    @Test
    fun `setIdleTimeout persists the selected minutes`() = runTest(testDispatcher) {
        val prefs = mockAppStatePrefs()
        val vm = createViewModel(PostSessionMode.SUMMARY, appStatePrefs = prefs)
        vm.setIdleTimeout(10)
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify { prefs.setIdleTimeoutMinutes(10) }
    }

    @Test
    fun `state reflects the stored idle timeout minutes`() = runTest(testDispatcher) {
        val prefs = mockAppStatePrefs(idleMinutes = 10)
        val vm = createViewModel(PostSessionMode.SUMMARY, appStatePrefs = prefs)
        vm.state.test {
            val state = awaitLoaded()
            assertEquals(10, state.idleTimeoutMinutes)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setQuickDisconnect persists the selected seconds`() = runTest(testDispatcher) {
        val prefs = mockAppStatePrefs()
        val vm = createViewModel(PostSessionMode.SUMMARY, appStatePrefs = prefs)
        vm.setQuickDisconnect(15)
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify { prefs.setQuickDisconnectSeconds(15) }
    }

    @Test
    fun `state reflects the stored quick disconnect seconds`() = runTest(testDispatcher) {
        val prefs = mockAppStatePrefs(quickSeconds = 60)
        val vm = createViewModel(PostSessionMode.SUMMARY, appStatePrefs = prefs)
        vm.state.test {
            val state = awaitLoaded()
            assertEquals(60, state.quickDisconnectSeconds)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `quick disconnect defaults to 30 seconds when unset`() = runTest(testDispatcher) {
        val prefs = mockAppStatePrefs()
        val vm = createViewModel(PostSessionMode.SUMMARY, appStatePrefs = prefs)
        vm.state.test {
            val state = awaitLoaded()
            assertEquals(
                AppStatePreferences.DEFAULT_QUICK_DISCONNECT_SECONDS,
                state.quickDisconnectSeconds
            )
            assertEquals(30, state.quickDisconnectSeconds)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setDisableTimeout persists the flag`() = runTest(testDispatcher) {
        val prefs = mockAppStatePrefs()
        val vm = createViewModel(PostSessionMode.SUMMARY, appStatePrefs = prefs)
        vm.setDisableTimeout(true)
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify { prefs.setIdleTimeoutDisabled(true) }
    }

    @Test
    fun `state reflects the stored disable-timeout flag`() = runTest(testDispatcher) {
        val prefs = mockAppStatePrefs(idleDisabled = true)
        val vm = createViewModel(PostSessionMode.SUMMARY, appStatePrefs = prefs)
        vm.state.test {
            val state = awaitLoaded()
            assertTrue(state.idleTimeoutDisabled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `canIgnoreDailyLimit surfaces the capability flag in state`() = runTest(testDispatcher) {
        val vm = createViewModel(PostSessionMode.SUMMARY, canIgnoreDailyLimit = true)
        vm.state.test {
            val state = awaitLoaded()
            assertTrue(state.canIgnoreDailyLimit)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `canIgnoreDailyLimit defaults to false (google build)`() = runTest(testDispatcher) {
        val vm = createViewModel(PostSessionMode.SUMMARY)
        vm.state.test {
            val state = awaitLoaded()
            assertFalse(state.canIgnoreDailyLimit)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `state reflects the stored ignore-daily-time-limit flag`() = runTest(testDispatcher) {
        val prefs = mockAppStatePrefs(ignoreDailyTimeLimit = true)
        val vm = createViewModel(
            PostSessionMode.SUMMARY,
            appStatePrefs = prefs,
            canIgnoreDailyLimit = true
        )
        vm.state.test {
            val state = awaitLoaded()
            assertTrue(state.ignoreDailyTimeLimit)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ignore-daily-time-limit defaults to false`() = runTest(testDispatcher) {
        val prefs = mockAppStatePrefs()
        val vm = createViewModel(PostSessionMode.SUMMARY, appStatePrefs = prefs)
        vm.state.test {
            val state = awaitLoaded()
            assertFalse(state.ignoreDailyTimeLimit)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setIgnoreDailyTimeLimit persists the flag`() = runTest(testDispatcher) {
        val prefs = mockAppStatePrefs()
        val vm = createViewModel(PostSessionMode.SUMMARY, appStatePrefs = prefs)
        vm.setIgnoreDailyTimeLimit(true)
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify { prefs.setIgnoreDailyTimeLimit(true) }
    }

    @Test
    fun `crash-reporting switch is absent without a consent seam (google)`() =
        runTest(testDispatcher) {
            val vm = createViewModel(PostSessionMode.SUMMARY)
            vm.state.test {
                val state = awaitLoaded()
                assertFalse(state.canControlCrashReporting)
                assertFalse(state.crashReportingEnabled)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `crash-reporting switch is present and off on a fresh foss install`() =
        runTest(testDispatcher) {
            val vm = createViewModel(
                PostSessionMode.SUMMARY,
                crashReportingPreference = fossCrashReportingPreference()
            )
            vm.state.test {
                val state = awaitLoaded()
                assertTrue(state.canControlCrashReporting)
                assertFalse(state.crashReportingEnabled)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `crash-reporting switch reflects the stored opt-in`() = runTest(testDispatcher) {
        val vm = createViewModel(
            PostSessionMode.SUMMARY,
            crashReportingPreference = fossCrashReportingPreference(
                preferences = inMemoryCrashReportingPreferences(optIn = true)
            )
        )
        vm.state.test {
            val state = awaitLoaded()
            assertTrue(state.crashReportingEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setCrashReportingEnabled enables collection and persists it`() = runTest(testDispatcher) {
        val reporter = RecordingCrashReporter()
        val store = inMemoryCrashReportingPreferences()
        val vm = createViewModel(
            PostSessionMode.SUMMARY,
            crashReportingPreference = fossCrashReportingPreference(reporter, store)
        )

        vm.setCrashReportingEnabled(true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(true), reporter.collectionEnabledCalls)
        assertTrue(store.crashReportingOptIn())
    }

    @Test
    fun `setCrashReportingEnabled off stops collection and persists it`() =
        runTest(testDispatcher) {
            val reporter = RecordingCrashReporter()
            val store = inMemoryCrashReportingPreferences(optIn = true)
            val vm = createViewModel(
                PostSessionMode.SUMMARY,
                crashReportingPreference = fossCrashReportingPreference(reporter, store)
            )

            vm.setCrashReportingEnabled(false)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf(false), reporter.collectionEnabledCalls)
            assertFalse(store.crashReportingOptIn())
        }

    private fun fossCrashReportingPreference(
        reporter: CrashReporter = RecordingCrashReporter(),
        preferences: AppStatePreferences = inMemoryCrashReportingPreferences()
    ) = CrashReportingPreference(
        preferences = preferences,
        crashReporter = reporter,
        requiresOptIn = true,
        isDebugBuild = false
    )

    @Test
    fun `battery exempt hides the warning`() = runTest(testDispatcher) {
        val vm = createViewModel(PostSessionMode.SUMMARY, batteryExempt = true)
        vm.state.test {
            val state = awaitLoaded()
            assertTrue(state.batteryOptimizationExempt)
            assertFalse("warning hidden when exempt", state.showBatteryWarning)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `not battery exempt shows the warning`() = runTest(testDispatcher) {
        val vm = createViewModel(PostSessionMode.SUMMARY, batteryExempt = false)
        vm.state.test {
            val state = awaitLoaded()
            assertFalse(state.batteryOptimizationExempt)
            assertTrue("warning shown when not exempt", state.showBatteryWarning)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `spurious wake stats default to zero`() = runTest(testDispatcher) {
        val vm = createViewModel(PostSessionMode.SUMMARY)
        vm.state.test {
            awaitItem() // loading
            val state = awaitItem()
            assertEquals(0, state.spuriousWakesLast24h)
            assertEquals(0L, state.totalWakesLifetime)
        }
    }

    @Test
    fun `computeOverallStatus returns correct values`() {
        assertEquals(
            TrustOverallStatus.VERIFIED,
            SettingsViewModel.computeOverallStatus("verified", "verified")
        )
        assertEquals(
            TrustOverallStatus.WARNING,
            SettingsViewModel.computeOverallStatus("warning", "verified")
        )
        assertEquals(
            TrustOverallStatus.WARNING,
            SettingsViewModel.computeOverallStatus("verified", "warning")
        )
        assertEquals(
            TrustOverallStatus.ALERT,
            SettingsViewModel.computeOverallStatus("alert", "verified")
        )
        assertEquals(
            TrustOverallStatus.ALERT,
            SettingsViewModel.computeOverallStatus("verified", "alert")
        )
        assertEquals(
            TrustOverallStatus.ALERT,
            SettingsViewModel.computeOverallStatus("warning", "alert")
        )
    }

    /** Drain the initial loading emission(s) and return the first loaded state. */
    private suspend fun ReceiveTurbine<SettingsState>.awaitLoaded(): SettingsState {
        var item = awaitItem()
        while (item.isLoading) item = awaitItem()
        return item
    }

    /**
     * Real DataStore-backed [AppStatePreferences], reset between tests — the
     * file persists across tests in the same VM.
     */
    private fun freshAppStatePrefs(): AppStatePreferences {
        val prefs = AppStatePreferences(ApplicationProvider.getApplicationContext())
        runBlocking { prefs.reset() }
        return prefs
    }

    private fun freshSecurityPrefs(): SecurityCheckPreferences {
        val prefs = SecurityCheckPreferences(ApplicationProvider.getApplicationContext())
        runBlocking {
            // Reset any state left over from a sibling test in the same VM.
            prefs.clearResults()
            prefs.setCertFingerprint("")
            prefs.setSecurityCheckEnabled(
                SecurityCheckPreferences.DEFAULT_SECURITY_CHECK_ENABLED
            )
        }
        return prefs
    }

    /**
     * Mock [AppStatePreferences] with controllable idle-timeout reads. Avoids the
     * real process-singleton DataStore, whose async writes would bleed between
     * tests sharing this Robolectric VM.
     */
    private fun mockAppStatePrefs(
        idleMinutes: Int = AppStatePreferences.DEFAULT_IDLE_TIMEOUT_MINUTES,
        idleDisabled: Boolean = false,
        quickSeconds: Int = AppStatePreferences.DEFAULT_QUICK_DISCONNECT_SECONDS,
        ignoreDailyTimeLimit: Boolean = false
    ): AppStatePreferences = mockk(relaxed = true) {
        every { observeIdleTimeoutMinutes() } returns flowOf(idleMinutes)
        every { observeIdleTimeoutDisabled() } returns flowOf(idleDisabled)
        every { observeQuickDisconnectSeconds() } returns flowOf(quickSeconds)
        every { observeIgnoreDailyTimeLimit() } returns flowOf(ignoreDailyTimeLimit)
        every { observeSecurityCheckIntervalHours() } returns
            flowOf(AppStatePreferences.DEFAULT_INTERVAL_HOURS)
    }

    private fun createViewModel(
        mode: PostSessionMode,
        securityPrefs: SecurityCheckPreferences? = null,
        appStatePrefs: AppStatePreferences? = null,
        batteryExempt: Boolean = false,
        canIgnoreDailyLimit: Boolean = false,
        crashReportingPreference: CrashReportingPreference? = null,
        applySchedule: (SecurityCheckIntervalOption) -> Unit = {}
    ): SettingsViewModel = createViewModel(
        mode,
        securityPrefs,
        provider = null,
        appStatePrefs = appStatePrefs,
        batteryExempt = batteryExempt,
        canIgnoreDailyLimit = canIgnoreDailyLimit,
        crashReportingPreference = crashReportingPreference,
        applySchedule = applySchedule
    )

    @Suppress("LongParameterList")
    private fun createViewModel(
        mode: PostSessionMode,
        securityPrefs: SecurityCheckPreferences?,
        provider: NotificationSettingsProvider?,
        spuriousWakesFlow: Flow<SpuriousWakeStats> = flowOf(SpuriousWakeStats.EMPTY),
        appStatePrefs: AppStatePreferences? = null,
        batteryExempt: Boolean = false,
        canIgnoreDailyLimit: Boolean = false,
        crashReportingPreference: CrashReportingPreference? = null,
        applySchedule: (SecurityCheckIntervalOption) -> Unit = {}
    ): SettingsViewModel {
        val resolvedProvider = provider ?: mockk {
            val s = NotificationSettings(
                postSessionMode = mode,
                notificationPermissionGranted = true
            )
            coEvery { settings() } returns s
            every { observeSettings() } returns flowOf(s)
            coEvery { setPostSessionMode(any()) } returns Unit
            coEvery { setShowAllMcpMessages(any()) } returns Unit
        }
        val themePref = mockk<ThemePreference> {
            every { themeMode } returns MutableStateFlow(ThemeMode.AUTO)
        }
        return SettingsViewModel(
            resolvedProvider,
            themePref,
            mockk(relaxed = true),
            mockk(relaxed = true),
            emptyList(),
            securityPrefs,
            appStatePrefs,
            batteryExemptProvider = { batteryExempt },
            spuriousWakesFlow = spuriousWakesFlow,
            canIgnoreDailyLimit = canIgnoreDailyLimit,
            crashReportingPreference = crashReportingPreference,
            applySecurityCheckSchedule = applySchedule
        )
    }
}

/**
 * Captures the check-interval choices [SettingsViewModel] pushes to
 * WorkManager, and lets a test wait for a given number of them.
 *
 * The waiting matters. `setSecurityCheckInterval` writes to a real DataStore
 * before it calls this seam, so its coroutine suspends on I/O that is not on
 * the test dispatcher; `advanceUntilIdle()` returns while the continuation is
 * still queued and the assertion reads a half-finished list. Suspending on a
 * [CompletableDeferred] instead lets the scheduler run those continuations,
 * and also guarantees the DataStore write has landed before the next test
 * resets the file.
 */
private class ScheduleRecorder : (SecurityCheckIntervalOption) -> Unit {
    private val applied = mutableListOf<SecurityCheckIntervalOption>()
    private val waiters = mutableMapOf<Int, CompletableDeferred<Unit>>()

    override fun invoke(interval: SecurityCheckIntervalOption) {
        applied += interval
        waiters.remove(applied.size)?.complete(Unit)
    }

    /** Suspends until [count] intervals have been applied, then returns them. */
    suspend fun awaitCount(count: Int): List<SecurityCheckIntervalOption> {
        if (applied.size < count) {
            waiters.getOrPut(count) { CompletableDeferred() }.await()
        }
        return applied.toList()
    }
}
