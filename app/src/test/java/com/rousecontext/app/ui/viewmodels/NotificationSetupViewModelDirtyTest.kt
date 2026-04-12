package com.rousecontext.app.ui.viewmodels

import android.content.Context
import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.app.state.IntegrationSettingsStore
import com.rousecontext.app.ui.screens.SetupMode
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
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
 * Covers dirty-tracking behaviour for the floating Save bar added in #59:
 * isDirty is false when the VM loads persisted settings, becomes true once
 * the user edits any field, and returns to false after [saveSettings].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationSetupViewModelDirtyTest {

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
    fun `isDirty is false immediately after initForMode loads persisted values`() =
        runTest(testDispatcher) {
            val settingsStore = mockk<IntegrationSettingsStore> {
                every {
                    getInt(
                        NotificationSetupViewModel.INTEGRATION_ID,
                        IntegrationSettingsStore.KEY_RETENTION_DAYS,
                        any()
                    )
                } returns 30
                every {
                    getBoolean(
                        NotificationSetupViewModel.INTEGRATION_ID,
                        IntegrationSettingsStore.KEY_ALLOW_ACTIONS,
                        any()
                    )
                } returns true
            }
            val vm = NotificationSetupViewModel(
                context = mockk(relaxed = true),
                stateStore = mockk(relaxed = true),
                settingsStore = settingsStore
            )

            vm.initForMode(SetupMode.SETTINGS)
            advanceUntilIdle()

            assertFalse(
                "Freshly-loaded settings must not be marked dirty",
                vm.isDirty.value
            )
        }

    @Test
    fun `isDirty becomes true after retentionDays is changed`() = runTest(testDispatcher) {
        val settingsStore = mockk<IntegrationSettingsStore> {
            every {
                getInt(
                    NotificationSetupViewModel.INTEGRATION_ID,
                    IntegrationSettingsStore.KEY_RETENTION_DAYS,
                    any()
                )
            } returns 7
            every {
                getBoolean(
                    NotificationSetupViewModel.INTEGRATION_ID,
                    IntegrationSettingsStore.KEY_ALLOW_ACTIONS,
                    any()
                )
            } returns false
        }
        val vm = NotificationSetupViewModel(
            context = mockk(relaxed = true),
            stateStore = mockk(relaxed = true),
            settingsStore = settingsStore
        )

        vm.initForMode(SetupMode.SETTINGS)
        advanceUntilIdle()
        vm.setRetentionDays(30)
        advanceUntilIdle()

        assertTrue("Editing retentionDays must flip isDirty to true", vm.isDirty.value)
    }

    @Test
    fun `isDirty becomes true after allowActions toggled`() = runTest(testDispatcher) {
        val settingsStore = mockk<IntegrationSettingsStore> {
            every {
                getInt(
                    NotificationSetupViewModel.INTEGRATION_ID,
                    IntegrationSettingsStore.KEY_RETENTION_DAYS,
                    any()
                )
            } returns 7
            every {
                getBoolean(
                    NotificationSetupViewModel.INTEGRATION_ID,
                    IntegrationSettingsStore.KEY_ALLOW_ACTIONS,
                    any()
                )
            } returns false
        }
        val vm = NotificationSetupViewModel(
            context = mockk(relaxed = true),
            stateStore = mockk(relaxed = true),
            settingsStore = settingsStore
        )

        vm.initForMode(SetupMode.SETTINGS)
        advanceUntilIdle()
        vm.setAllowActions(true)
        advanceUntilIdle()

        assertTrue("Toggling allowActions must flip isDirty to true", vm.isDirty.value)
    }

    @Test
    fun `isDirty returns to false after saveSettings persists the change`() =
        runTest(testDispatcher) {
            val settingsStore = mockk<IntegrationSettingsStore> {
                every {
                    getInt(
                        NotificationSetupViewModel.INTEGRATION_ID,
                        IntegrationSettingsStore.KEY_RETENTION_DAYS,
                        any()
                    )
                } returns 7
                every {
                    getBoolean(
                        NotificationSetupViewModel.INTEGRATION_ID,
                        IntegrationSettingsStore.KEY_ALLOW_ACTIONS,
                        any()
                    )
                } returns false
                every { setInt(any(), any(), any()) } just runs
                every { setBoolean(any(), any(), any()) } just runs
            }
            val vm = NotificationSetupViewModel(
                context = mockk(relaxed = true),
                stateStore = mockk(relaxed = true),
                settingsStore = settingsStore
            )

            vm.initForMode(SetupMode.SETTINGS)
            advanceUntilIdle()
            vm.setRetentionDays(30)
            advanceUntilIdle()
            assertTrue(vm.isDirty.value)

            vm.saveSettings()
            advanceUntilIdle()

            assertFalse(
                "After saveSettings the new value is the saved snapshot; isDirty must clear",
                vm.isDirty.value
            )
            verify {
                settingsStore.setInt(
                    NotificationSetupViewModel.INTEGRATION_ID,
                    IntegrationSettingsStore.KEY_RETENTION_DAYS,
                    30
                )
            }
        }

    @Test
    fun `isDirty flips back to false if user edits then reverts to saved value`() =
        runTest(testDispatcher) {
            val settingsStore = mockk<IntegrationSettingsStore> {
                every {
                    getInt(
                        NotificationSetupViewModel.INTEGRATION_ID,
                        IntegrationSettingsStore.KEY_RETENTION_DAYS,
                        any()
                    )
                } returns 7
                every {
                    getBoolean(
                        NotificationSetupViewModel.INTEGRATION_ID,
                        IntegrationSettingsStore.KEY_ALLOW_ACTIONS,
                        any()
                    )
                } returns false
            }
            val vm = NotificationSetupViewModel(
                context = mockk<Context>(relaxed = true),
                stateStore = mockk<IntegrationStateStore>(relaxed = true),
                settingsStore = settingsStore
            )

            vm.initForMode(SetupMode.SETTINGS)
            advanceUntilIdle()

            vm.setRetentionDays(30)
            advanceUntilIdle()
            assertTrue(vm.isDirty.value)

            vm.setRetentionDays(7)
            advanceUntilIdle()
            assertFalse(
                "Reverting to the saved value should clear isDirty without saving",
                vm.isDirty.value
            )
        }
}
