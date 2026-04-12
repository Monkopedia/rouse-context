package com.rousecontext.app.ui.viewmodels

import android.content.SharedPreferences
import app.cash.turbine.test
import com.rousecontext.api.NotificationSettings
import com.rousecontext.api.NotificationSettingsProvider
import com.rousecontext.api.PostSessionMode
import com.rousecontext.app.state.ThemeMode
import com.rousecontext.app.state.ThemePreference
import com.rousecontext.app.ui.screens.TrustOverallStatus
import com.rousecontext.work.SecurityCheckWorker
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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

@OptIn(ExperimentalCoroutinesApi::class)
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
            assertEquals("Summary", state.postSessionMode)
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
            assertEquals("Each usage", state.postSessionMode)
        }
    }

    @Test
    fun `state reflects suppress mode`() = runTest(testDispatcher) {
        val vm = createViewModel(PostSessionMode.SUPPRESS)
        vm.state.test {
            awaitItem()
            val state = awaitItem()
            assertEquals("Suppress", state.postSessionMode)
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
        val prefs = createMockPrefs(lastCheckTime = 0L)
        val vm = createViewModel(PostSessionMode.SUMMARY, securityPrefs = prefs)
        vm.state.test {
            val state = awaitItem()
            assertNull(state.trustStatus)
        }
    }

    @Test
    fun `trust status shows verified when both checks pass`() = runTest(testDispatcher) {
        val prefs = createMockPrefs(
            lastCheckTime = 1000L,
            selfResult = "verified",
            ctResult = "verified",
            fingerprint = "AA:BB:CC"
        )
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
        val prefs = createMockPrefs(
            lastCheckTime = 1000L,
            selfResult = "warning",
            ctResult = "verified"
        )
        val vm = createViewModel(PostSessionMode.SUMMARY, securityPrefs = prefs)
        vm.state.test {
            awaitItem()
            val state = awaitItem()
            assertEquals(TrustOverallStatus.WARNING, state.trustStatus?.overallStatus)
        }
    }

    @Test
    fun `trust status shows alert when ct check alerts`() = runTest(testDispatcher) {
        val prefs = createMockPrefs(
            lastCheckTime = 1000L,
            selfResult = "verified",
            ctResult = "alert"
        )
        val vm = createViewModel(PostSessionMode.SUMMARY, securityPrefs = prefs)
        vm.state.test {
            awaitItem()
            val state = awaitItem()
            assertEquals(TrustOverallStatus.ALERT, state.trustStatus?.overallStatus)
        }
    }

    @Test
    fun `alert takes precedence over warning`() = runTest(testDispatcher) {
        val prefs = createMockPrefs(
            lastCheckTime = 1000L,
            selfResult = "warning",
            ctResult = "alert"
        )
        val vm = createViewModel(PostSessionMode.SUMMARY, securityPrefs = prefs)
        vm.state.test {
            awaitItem()
            val state = awaitItem()
            assertEquals(TrustOverallStatus.ALERT, state.trustStatus?.overallStatus)
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

    private fun createViewModel(
        mode: PostSessionMode,
        securityPrefs: SharedPreferences? = null
    ): SettingsViewModel {
        val provider = mockk<NotificationSettingsProvider> {
            every { settings } returns NotificationSettings(
                postSessionMode = mode,
                notificationPermissionGranted = true
            )
        }
        val themePref = mockk<ThemePreference> {
            every { themeMode } returns MutableStateFlow(ThemeMode.AUTO)
        }
        return SettingsViewModel(
            provider,
            themePref,
            mockk(relaxed = true),
            mockk(relaxed = true),
            emptyList(),
            securityPrefs
        )
    }

    private fun createMockPrefs(
        lastCheckTime: Long = 0L,
        selfResult: String = "",
        ctResult: String = "",
        fingerprint: String = ""
    ): SharedPreferences = mockk {
        every { getLong(SecurityCheckWorker.KEY_LAST_CHECK_TIME, 0L) } returns lastCheckTime
        every {
            getString(SecurityCheckWorker.KEY_SELF_CERT_RESULT, "")
        } returns selfResult
        every {
            getString(SecurityCheckWorker.KEY_CT_LOG_RESULT, "")
        } returns ctResult
        every {
            getString(SettingsViewModel.KEY_CERT_FINGERPRINT, "")
        } returns fingerprint
    }
}
