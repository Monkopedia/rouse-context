package com.rousecontext.app.ui.viewmodels

import app.cash.turbine.test
import com.rousecontext.api.NotificationSettings
import com.rousecontext.api.NotificationSettingsProvider
import com.rousecontext.api.PostSessionMode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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

    private fun createViewModel(mode: PostSessionMode): SettingsViewModel {
        val provider = mockk<NotificationSettingsProvider> {
            every { settings } returns NotificationSettings(
                postSessionMode = mode,
                notificationPermissionGranted = true
            )
        }
        return SettingsViewModel(provider)
    }
}
