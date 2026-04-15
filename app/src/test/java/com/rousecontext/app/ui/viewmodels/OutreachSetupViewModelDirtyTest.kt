package com.rousecontext.app.ui.viewmodels

import com.rousecontext.app.state.IntegrationSettingsStore
import com.rousecontext.app.ui.screens.SetupMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers dirty-tracking behaviour for the floating Save bar added in #59 on
 * the Outreach settings screen: isDirty is false after load, becomes true on
 * edit, and returns to false after saveSettings.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OutreachSetupViewModelDirtyTest {

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
    fun `isDirty is false immediately after initForMode loads persisted value`() =
        runTest(testDispatcher) {
            val settingsStore = mockk<IntegrationSettingsStore> {
                coEvery {
                    getBoolean(
                        OutreachSetupViewModel.INTEGRATION_ID,
                        IntegrationSettingsStore.KEY_DND_TOGGLED,
                        any()
                    )
                } returns true
                coEvery {
                    getBoolean(
                        OutreachSetupViewModel.INTEGRATION_ID,
                        IntegrationSettingsStore.KEY_DIRECT_LAUNCH_ENABLED,
                        any()
                    )
                } returns false
            }
            val vm = OutreachSetupViewModel(
                context = mockk(relaxed = true),
                stateStore = mockk(relaxed = true),
                settingsStore = settingsStore
            )

            vm.initForMode(SetupMode.SETTINGS)
            advanceUntilIdle()

            assertFalse(vm.isDirty.value)
        }

    @Test
    fun `isDirty becomes true after DND toggle flips`() = runTest(testDispatcher) {
        val settingsStore = mockk<IntegrationSettingsStore> {
            coEvery { getBoolean(any(), any(), any()) } returns false
        }
        val vm = OutreachSetupViewModel(
            context = mockk(relaxed = true),
            stateStore = mockk(relaxed = true),
            settingsStore = settingsStore
        )

        vm.initForMode(SetupMode.SETTINGS)
        advanceUntilIdle()

        vm.setDndToggled(true)
        advanceUntilIdle()

        assertTrue(vm.isDirty.value)
    }

    @Test
    fun `isDirty returns to false after saveSettings`() = runTest(testDispatcher) {
        val settingsStore = mockk<IntegrationSettingsStore> {
            coEvery { getBoolean(any(), any(), any()) } returns false
            coEvery { setBoolean(any(), any(), any()) } just runs
        }
        val vm = OutreachSetupViewModel(
            context = mockk(relaxed = true),
            stateStore = mockk(relaxed = true),
            settingsStore = settingsStore
        )

        vm.initForMode(SetupMode.SETTINGS)
        advanceUntilIdle()
        vm.setDndToggled(true)
        advanceUntilIdle()
        assertTrue(vm.isDirty.value)

        vm.saveSettings()
        advanceUntilIdle()

        assertFalse(vm.isDirty.value)
        coVerify {
            settingsStore.setBoolean(
                OutreachSetupViewModel.INTEGRATION_ID,
                IntegrationSettingsStore.KEY_DND_TOGGLED,
                true
            )
        }
    }

    @Test
    fun `directLaunchEnabled toggle flips isDirty and persists on save`() =
        runTest(testDispatcher) {
            val settingsStore = mockk<IntegrationSettingsStore> {
                coEvery { getBoolean(any(), any(), any()) } returns false
                coEvery { setBoolean(any(), any(), any()) } just runs
            }
            val vm = OutreachSetupViewModel(
                context = mockk(relaxed = true),
                stateStore = mockk(relaxed = true),
                settingsStore = settingsStore
            )

            vm.initForMode(SetupMode.SETTINGS)
            advanceUntilIdle()
            assertFalse(vm.isDirty.value)

            vm.setDirectLaunchEnabled(true)
            advanceUntilIdle()
            assertTrue(vm.isDirty.value)
            assertTrue(vm.state.value.directLaunchEnabled)

            vm.saveSettings()
            advanceUntilIdle()

            assertFalse(vm.isDirty.value)
            coVerify {
                settingsStore.setBoolean(
                    OutreachSetupViewModel.INTEGRATION_ID,
                    IntegrationSettingsStore.KEY_DIRECT_LAUNCH_ENABLED,
                    true
                )
            }
        }

    @Test
    fun `initForMode SETTINGS loads directLaunchEnabled from store`() = runTest(testDispatcher) {
        val settingsStore = mockk<IntegrationSettingsStore> {
            coEvery {
                getBoolean(
                    OutreachSetupViewModel.INTEGRATION_ID,
                    IntegrationSettingsStore.KEY_DND_TOGGLED,
                    any()
                )
            } returns false
            coEvery {
                getBoolean(
                    OutreachSetupViewModel.INTEGRATION_ID,
                    IntegrationSettingsStore.KEY_DIRECT_LAUNCH_ENABLED,
                    any()
                )
            } returns true
        }
        val vm = OutreachSetupViewModel(
            context = mockk(relaxed = true),
            stateStore = mockk(relaxed = true),
            settingsStore = settingsStore
        )

        vm.initForMode(SetupMode.SETTINGS)
        advanceUntilIdle()

        assertTrue(vm.state.value.directLaunchEnabled)
        assertFalse(vm.isDirty.value)
    }

    @Test
    fun `isDirty flips back to false if user edits then reverts to saved value`() =
        runTest(testDispatcher) {
            val settingsStore = mockk<IntegrationSettingsStore> {
                coEvery { getBoolean(any(), any(), any()) } returns false
            }
            val vm = OutreachSetupViewModel(
                context = mockk(relaxed = true),
                stateStore = mockk(relaxed = true),
                settingsStore = settingsStore
            )

            vm.initForMode(SetupMode.SETTINGS)
            advanceUntilIdle()

            vm.setDndToggled(true)
            advanceUntilIdle()
            assertTrue(vm.isDirty.value)

            vm.setDndToggled(false)
            advanceUntilIdle()
            assertFalse(vm.isDirty.value)
        }
}
