package com.rousecontext.app.ui.viewmodels

import app.cash.turbine.test
import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.integrations.health.HEALTH_DATA_HISTORY_PERMISSION
import com.rousecontext.integrations.health.HealthConnectRepository
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HealthConnectSetupViewModelTest {

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
    fun `initial state reports historical access not granted`() = runTest(testDispatcher) {
        val repo = FakeRepo().apply {
            historicalReadGranted = false
        }
        val vm = HealthConnectSetupViewModel(FakeStore(), repo)

        vm.historicalAccessGranted.test {
            assertFalse(awaitItem())
        }
    }

    @Test
    fun `refresh emits granted when repo reports granted`() = runTest(testDispatcher) {
        val repo = FakeRepo().apply {
            historicalReadGranted = true
        }
        val vm = HealthConnectSetupViewModel(FakeStore(), repo)

        vm.refreshPermissions()
        advanceUntilIdle()

        vm.historicalAccessGranted.test {
            assertTrue(awaitItem())
        }
    }

    @Test
    fun `refresh emits not granted after permission revoked`() = runTest(testDispatcher) {
        val repo = FakeRepo().apply {
            historicalReadGranted = true
        }
        val vm = HealthConnectSetupViewModel(FakeStore(), repo)

        vm.refreshPermissions()
        advanceUntilIdle()
        assertTrue(vm.historicalAccessGranted.value)

        repo.historicalReadGranted = false
        vm.refreshPermissions()
        advanceUntilIdle()
        assertFalse(vm.historicalAccessGranted.value)
    }

    @Test
    fun `refreshPermissions exposes granted record types from repo`() = runTest(testDispatcher) {
        val repo = FakeRepo().apply {
            grantedRecordTypes = setOf("Steps", "HeartRate")
        }
        val vm = HealthConnectSetupViewModel(FakeStore(), repo)

        vm.refreshPermissions()
        advanceUntilIdle()

        assertEquals(setOf("Steps", "HeartRate"), vm.grantedRecordTypes.value)
    }

    @Test
    fun `initial grantedRecordTypes is empty`() {
        val vm = HealthConnectSetupViewModel(FakeStore(), FakeRepo())
        assertEquals(emptySet<String>(), vm.grantedRecordTypes.value)
    }

    @Test
    fun `refreshPermissions updates grantedRecordTypes after revocation`() =
        runTest(testDispatcher) {
            val repo = FakeRepo().apply {
                grantedRecordTypes = setOf("Steps", "HeartRate", "SleepSession")
            }
            val vm = HealthConnectSetupViewModel(FakeStore(), repo)

            vm.refreshPermissions()
            advanceUntilIdle()
            assertEquals(3, vm.grantedRecordTypes.value.size)

            repo.grantedRecordTypes = setOf("Steps")
            vm.refreshPermissions()
            advanceUntilIdle()
            assertEquals(setOf("Steps"), vm.grantedRecordTypes.value)
        }

    @Test
    fun `onPermissionsResult triggers refresh of granted record types`() = runTest(testDispatcher) {
        val repo = FakeRepo().apply {
            grantedRecordTypes = setOf("Steps", "HeartRate")
        }
        val vm = HealthConnectSetupViewModel(FakeStore(), repo)

        vm.onPermissionsResult(
            setOf("android.permission.health.READ_STEPS")
        )
        advanceUntilIdle()

        assertEquals(setOf("Steps", "HeartRate"), vm.grantedRecordTypes.value)
    }

    @Test
    fun `refreshPermissions swallows repo exceptions and leaves state empty`() =
        runTest(testDispatcher) {
            val repo = FakeRepo().apply {
                throwOnGranted = true
            }
            val vm = HealthConnectSetupViewModel(FakeStore(), repo)

            vm.refreshPermissions()
            advanceUntilIdle()

            assertEquals(emptySet<String>(), vm.grantedRecordTypes.value)
            assertFalse(vm.historicalAccessGranted.value)
        }

    @Test
    fun `onPermissionsResult updates historical state from granted set`() =
        runTest(testDispatcher) {
            val repo = FakeRepo()
            val vm = HealthConnectSetupViewModel(FakeStore(), repo)

            val enabled = vm.onPermissionsResult(
                setOf("android.permission.health.READ_STEPS", HEALTH_DATA_HISTORY_PERMISSION)
            )

            assertTrue(enabled)
            assertTrue(vm.historicalAccessGranted.value)
        }

    @Test
    fun `onPermissionsResult without history perm keeps historical state false`() =
        runTest(testDispatcher) {
            val repo = FakeRepo().apply { historicalReadGranted = false }
            val vm = HealthConnectSetupViewModel(FakeStore(), repo)

            val enabled = vm.onPermissionsResult(setOf("android.permission.health.READ_STEPS"))

            assertTrue(enabled)
            assertFalse(vm.historicalAccessGranted.value)
        }

    @Test
    fun `historyPermission exposes the canonical permission string`() {
        assertEquals(
            "android.permission.health.READ_HEALTH_DATA_HISTORY",
            HealthConnectSetupViewModel.HISTORY_PERMISSION
        )
    }

    @Test
    fun `onHistoricalPermissionResult with history perm sets flag true`() =
        runTest(testDispatcher) {
            val vm = HealthConnectSetupViewModel(FakeStore(), FakeRepo())
            assertFalse(vm.historicalAccessGranted.value)

            vm.onHistoricalPermissionResult(setOf(HEALTH_DATA_HISTORY_PERMISSION))

            assertTrue(vm.historicalAccessGranted.value)
        }

    @Test
    fun `onHistoricalPermissionResult with empty set sets flag false`() = runTest(testDispatcher) {
        val vm = HealthConnectSetupViewModel(FakeStore(), FakeRepo())
        // Seed flag to true so the empty-set call has something to flip.
        vm.onHistoricalPermissionResult(setOf(HEALTH_DATA_HISTORY_PERMISSION))
        assertTrue(vm.historicalAccessGranted.value)

        vm.onHistoricalPermissionResult(emptySet())

        assertFalse(vm.historicalAccessGranted.value)
    }

    @Test
    fun `onHistoricalPermissionResult without history perm sets flag false`() =
        runTest(testDispatcher) {
            val vm = HealthConnectSetupViewModel(FakeStore(), FakeRepo())
            vm.onHistoricalPermissionResult(setOf(HEALTH_DATA_HISTORY_PERMISSION))
            assertTrue(vm.historicalAccessGranted.value)

            vm.onHistoricalPermissionResult(setOf("android.permission.health.READ_STEPS"))

            assertFalse(vm.historicalAccessGranted.value)
        }

    /**
     * Documents an intentional edge case: if the user previously granted the
     * historical-read permission via the dedicated history flow, and then
     * subsequently opens the main grant dialog and denies everything,
     * [HealthConnectSetupViewModel.onPermissionsResult] unconditionally
     * re-derives the flag from the granted set it receives, flipping
     * `historicalAccessGranted` back to `false`.
     *
     * This is by design: the main-grant result is treated as the
     * authoritative snapshot of currently-granted permissions. The flag is
     * not "sticky" across a denial.
     */
    @Test
    fun `onPermissionsResult with empty set overrides prior historical grant`() =
        runTest(testDispatcher) {
            val vm = HealthConnectSetupViewModel(FakeStore(), FakeRepo())
            // Simulate: user granted history via the history-specific flow.
            vm.onHistoricalPermissionResult(setOf(HEALTH_DATA_HISTORY_PERMISSION))
            assertTrue(vm.historicalAccessGranted.value)

            // Now the user opens the main grant dialog and denies everything.
            val enabled = vm.onPermissionsResult(emptySet())

            assertFalse(enabled)
            // Historical flag is re-derived from the (empty) granted set,
            // so it flips to false even though the user previously granted it.
            assertFalse(vm.historicalAccessGranted.value)
        }

    private class FakeRepo : HealthConnectRepository {
        var historicalReadGranted: Boolean = false
        var grantedRecordTypes: Set<String> = emptySet()
        var throwOnGranted: Boolean = false
        override suspend fun queryRecords(
            recordType: String,
            from: Instant,
            to: Instant,
            limit: Int?
        ): List<JsonObject> = emptyList()
        override suspend fun getGrantedPermissions(): Set<String> {
            if (throwOnGranted) error("boom")
            return grantedRecordTypes
        }
        override suspend fun isHistoricalReadGranted(): Boolean {
            if (throwOnGranted) error("boom")
            return historicalReadGranted
        }
        override suspend fun getSummary(from: Instant, to: Instant): JsonObject =
            JsonObject(emptyMap())
    }

    private class FakeStore : IntegrationStateStore {
        val enabled = mutableMapOf<String, Boolean>()
        override suspend fun isUserEnabled(integrationId: String): Boolean =
            enabled[integrationId] == true
        override suspend fun setUserEnabled(integrationId: String, enabled: Boolean) {
            this.enabled[integrationId] = enabled
        }
        override fun observeUserEnabled(integrationId: String) =
            MutableStateFlow(enabled[integrationId] == true)
        override suspend fun wasEverEnabled(integrationId: String): Boolean =
            enabled[integrationId] == true
        override fun observeEverEnabled(integrationId: String): Flow<Boolean> =
            MutableStateFlow(enabled[integrationId] == true)
        override fun observeChanges(): Flow<Unit> = MutableStateFlow(0).map { }
    }
}
