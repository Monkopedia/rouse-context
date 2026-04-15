package com.rousecontext.app.ui.viewmodels

import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.rousecontext.api.NotificationSettings
import com.rousecontext.api.NotificationSettingsProvider
import com.rousecontext.api.PostSessionMode
import com.rousecontext.app.state.AppStatePreferences
import com.rousecontext.app.state.ThemeMode
import com.rousecontext.app.state.ThemePreference
import com.rousecontext.app.ui.screens.PostSessionModeOption
import com.rousecontext.app.ui.screens.TrustOverallStatus
import com.rousecontext.work.SecurityCheckPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

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
            if (!first) {
                assertTrue(awaitItem())
            } else {
                assertTrue(first)
            }
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

    private fun freshSecurityPrefs(): SecurityCheckPreferences {
        val prefs = SecurityCheckPreferences(ApplicationProvider.getApplicationContext())
        runBlocking {
            // Reset any state left over from a sibling test in the same VM.
            prefs.clearResults()
            prefs.setCertFingerprint("")
        }
        return prefs
    }

    private fun createViewModel(
        mode: PostSessionMode,
        securityPrefs: SecurityCheckPreferences? = null
    ): SettingsViewModel = createViewModel(mode, securityPrefs, provider = null)

    private fun createViewModel(
        mode: PostSessionMode,
        securityPrefs: SecurityCheckPreferences?,
        provider: NotificationSettingsProvider?,
        spuriousWakesFlow: Flow<SpuriousWakeStats> = flowOf(SpuriousWakeStats.EMPTY)
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
        val appStatePrefs: AppStatePreferences? = null
        return SettingsViewModel(
            resolvedProvider,
            themePref,
            mockk(relaxed = true),
            mockk(relaxed = true),
            emptyList(),
            securityPrefs,
            appStatePrefs,
            spuriousWakesFlow = spuriousWakesFlow
        )
    }
}
