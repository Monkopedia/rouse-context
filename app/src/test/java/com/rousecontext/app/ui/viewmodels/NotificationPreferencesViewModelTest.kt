package com.rousecontext.app.ui.viewmodels

import app.cash.turbine.test
import com.rousecontext.api.NotificationSettings
import com.rousecontext.api.NotificationSettingsProvider
import com.rousecontext.api.PostSessionMode
import com.rousecontext.app.ui.screens.NotificationMode
import io.mockk.coEvery
import io.mockk.coVerify
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
class NotificationPreferencesViewModelTest {

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
    fun `initial state reflects persisted mode`() = runTest(testDispatcher) {
        val provider = mockk<NotificationSettingsProvider> {
            coEvery { settings() } returns NotificationSettings(
                postSessionMode = PostSessionMode.EACH_USAGE,
                notificationPermissionGranted = true
            )
            coEvery { setPostSessionMode(any()) } returns Unit
        }
        val vm = NotificationPreferencesViewModel(provider)
        vm.state.test {
            assertEquals(NotificationMode.SUMMARY, awaitItem().selectedMode)
            assertEquals(NotificationMode.EACH_USAGE, awaitItem().selectedMode)
        }
    }

    @Test
    fun `select updates state without persisting`() = runTest(testDispatcher) {
        val provider = mockk<NotificationSettingsProvider> {
            coEvery { settings() } returns NotificationSettings(
                postSessionMode = PostSessionMode.SUMMARY,
                notificationPermissionGranted = true
            )
            coEvery { setPostSessionMode(any()) } returns Unit
        }
        val vm = NotificationPreferencesViewModel(provider)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.select(NotificationMode.SUPPRESS)
        assertEquals(NotificationMode.SUPPRESS, vm.state.value.selectedMode)
        coVerify(exactly = 0) { provider.setPostSessionMode(any()) }
    }

    @Test
    fun `persistSelection writes current selection to provider`() = runTest(testDispatcher) {
        val provider = mockk<NotificationSettingsProvider> {
            coEvery { settings() } returns NotificationSettings(
                postSessionMode = PostSessionMode.SUMMARY,
                notificationPermissionGranted = true
            )
            coEvery { setPostSessionMode(any()) } returns Unit
        }
        val vm = NotificationPreferencesViewModel(provider)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.select(NotificationMode.SUPPRESS)
        vm.persistSelection()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { provider.setPostSessionMode(PostSessionMode.SUPPRESS) }
    }

    @Test
    fun `persistSelection maps each_usage`() = runTest(testDispatcher) {
        val provider = mockk<NotificationSettingsProvider> {
            coEvery { settings() } returns NotificationSettings(
                postSessionMode = PostSessionMode.SUMMARY,
                notificationPermissionGranted = true
            )
            coEvery { setPostSessionMode(any()) } returns Unit
        }
        val vm = NotificationPreferencesViewModel(provider)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.select(NotificationMode.EACH_USAGE)
        vm.persistSelection()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { provider.setPostSessionMode(PostSessionMode.EACH_USAGE) }
    }
}
