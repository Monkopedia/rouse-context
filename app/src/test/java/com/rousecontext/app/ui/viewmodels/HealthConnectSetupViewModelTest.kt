package com.rousecontext.app.ui.viewmodels

import app.cash.turbine.test
import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.mcp.health.HEALTH_DATA_HISTORY_PERMISSION
import com.rousecontext.mcp.health.HealthConnectRepository
import java.time.Instant
import kotlinx.serialization.json.JsonObject
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

        vm.refreshHistoricalAccess()
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

        vm.refreshHistoricalAccess()
        advanceUntilIdle()
        assertTrue(vm.historicalAccessGranted.value)

        repo.historicalReadGranted = false
        vm.refreshHistoricalAccess()
        advanceUntilIdle()
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

    private class FakeRepo : HealthConnectRepository {
        var historicalReadGranted: Boolean = false
        override suspend fun queryRecords(
            recordType: String,
            from: Instant,
            to: Instant,
            limit: Int?
        ): List<JsonObject> = emptyList()
        override suspend fun getGrantedPermissions(): Set<String> = emptySet()
        override suspend fun isHistoricalReadGranted(): Boolean = historicalReadGranted
        override suspend fun getSummary(from: Instant, to: Instant): JsonObject =
            JsonObject(emptyMap())
    }

    private class FakeStore : IntegrationStateStore {
        val enabled = mutableMapOf<String, Boolean>()
        override fun isUserEnabled(integrationId: String): Boolean =
            enabled[integrationId] == true
        override fun setUserEnabled(integrationId: String, enabled: Boolean) {
            this.enabled[integrationId] = enabled
        }
        override fun observeUserEnabled(integrationId: String) =
            MutableStateFlow(enabled[integrationId] == true)
        override fun wasEverEnabled(integrationId: String): Boolean =
            enabled[integrationId] == true
        override fun observeChanges(): Flow<Unit> =
            MutableStateFlow(0).map { }
    }
}
