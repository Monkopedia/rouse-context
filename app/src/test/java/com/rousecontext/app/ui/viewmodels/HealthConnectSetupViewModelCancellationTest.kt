package com.rousecontext.app.ui.viewmodels

import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.app.testing.MainDispatcherRule
import com.rousecontext.integrations.health.BucketResult
import com.rousecontext.integrations.health.HealthConnectRepository
import com.rousecontext.integrations.health.QueryResult
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Pins both directions of the two broad catches in
 * [HealthConnectSetupViewModel.refreshPermissions] (issue #667).
 *
 * These are the only two sites in that issue's sweep that could genuinely take
 * delivery of cancellation: `HealthConnectRepository`'s permission calls bottom
 * out in `permissionController.getGrantedPermissions()`, a real IPC hop, so a
 * `viewModelScope` cancelled by `onCleared()` was landing in the fallbacks and
 * being turned into "no permissions granted".
 *
 * **The cancellation cases and the [IllegalStateException] cases are a
 * discriminating PAIR and neither half is redundant.** `CancellationException`
 * extends `IllegalStateException` on the JVM, so a guard mis-written as
 * `catch (e: IllegalStateException) { throw e }` passes every cancellation case
 * here while wrongly rethrowing genuine Health Connect failures — only the ISE
 * cases catch that widening. Do not weaken them to `RuntimeException`, and do
 * not move the cancellation clause below the broad catch: Kotlin does not
 * diagnose the resulting dead clause, and only the cancellation cases here do.
 *
 * Propagation is observed through the ViewModel's own state rather than a
 * thrown exception, because `refreshPermissions` launches into `viewModelScope`
 * and a `CancellationException` escaping a launched coroutine is absorbed
 * silently. The discriminators are chosen so "rethrown" and "swallowed" produce
 * different [HealthConnectSetupViewModel] state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HealthConnectSetupViewModelCancellationTest {

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    // --- isHistoricalReadGranted (the first fallback) ---

    @Test
    fun `historical-read cancellation unwinds instead of falling into the second call`() =
        runTest(testDispatcher) {
            val repo = FakeRepo(
                historicalFailure = { CancellationException("viewModelScope cleared") },
                grantedRecordTypes = setOf("Steps")
            )
            val vm = HealthConnectSetupViewModel(FakeStore(), repo)

            vm.refreshPermissions()
            advanceUntilIdle()

            assertEquals(
                "cancellation from isHistoricalReadGranted must propagate out of " +
                    "refreshPermissions, but execution continued into " +
                    "getGrantedPermissions -- the broad catch swallowed it",
                0,
                repo.grantedPermissionsCalls
            )
            assertEquals(
                "cancellation must leave grantedRecordTypes untouched; it was published",
                emptySet<String>(),
                vm.grantedRecordTypes.value
            )
        }

    @Test
    fun `historical-read IllegalStateException is still swallowed into the false fallback`() =
        runTest(testDispatcher) {
            val repo = FakeRepo(
                historicalFailure = { IllegalStateException("health connect unavailable") },
                grantedRecordTypes = setOf("Steps")
            )
            val vm = HealthConnectSetupViewModel(FakeStore(), repo)

            vm.refreshPermissions()
            advanceUntilIdle()

            assertEquals(
                "an ordinary IllegalStateException must still degrade to the `false` " +
                    "fallback and continue to getGrantedPermissions -- the cancellation " +
                    "guard has been widened to a supertype and is now rethrowing " +
                    "genuine Health Connect failures",
                1,
                repo.grantedPermissionsCalls
            )
            assertFalse(vm.historicalAccessGranted.value)
            assertEquals(setOf("Steps"), vm.grantedRecordTypes.value)
        }

    // --- getGrantedPermissions (the second fallback) ---

    @Test
    fun `granted-permissions cancellation leaves both flows unpublished`() =
        runTest(testDispatcher) {
            val repo = FakeRepo(
                historicalReadGranted = true,
                grantedFailure = { CancellationException("viewModelScope cleared") }
            )
            val vm = HealthConnectSetupViewModel(FakeStore(), repo)

            vm.refreshPermissions()
            advanceUntilIdle()

            assertFalse(
                "cancellation from getGrantedPermissions must propagate out of " +
                    "refreshPermissions, but the historical flag was published anyway -- " +
                    "the broad catch swallowed it and let the writes run",
                vm.historicalAccessGranted.value
            )
            assertEquals(emptySet<String>(), vm.grantedRecordTypes.value)
        }

    @Test
    fun `granted-permissions IllegalStateException is still swallowed into the empty fallback`() =
        runTest(testDispatcher) {
            val repo = FakeRepo(
                historicalReadGranted = true,
                grantedFailure = { IllegalStateException("health connect unavailable") }
            )
            val vm = HealthConnectSetupViewModel(FakeStore(), repo)

            vm.refreshPermissions()
            advanceUntilIdle()

            assertTrue(
                "an ordinary IllegalStateException must still degrade to the empty-set " +
                    "fallback and let both writes run -- the cancellation guard has been " +
                    "widened to a supertype and is now rethrowing genuine Health Connect " +
                    "failures",
                vm.historicalAccessGranted.value
            )
            assertEquals(emptySet<String>(), vm.grantedRecordTypes.value)
        }
}

private class FakeRepo(
    private val historicalReadGranted: Boolean = false,
    private val grantedRecordTypes: Set<String> = emptySet(),
    private val historicalFailure: (() -> Throwable)? = null,
    private val grantedFailure: (() -> Throwable)? = null
) : HealthConnectRepository {

    var grantedPermissionsCalls: Int = 0
        private set

    override suspend fun queryRecords(
        recordType: String,
        from: Instant,
        to: Instant,
        limit: Int?
    ): QueryResult = QueryResult.Records(emptyList(), 0, false)

    override suspend fun bucketRecords(
        recordType: String,
        from: Instant,
        to: Instant,
        bucket: Duration
    ): BucketResult = BucketResult.Success(emptyList(), 0)

    override suspend fun getGrantedPermissions(): Set<String> {
        grantedPermissionsCalls++
        grantedFailure?.let { throw it() }
        return grantedRecordTypes
    }

    override suspend fun isHistoricalReadGranted(): Boolean {
        historicalFailure?.let { throw it() }
        return historicalReadGranted
    }

    override suspend fun getSummary(from: Instant, to: Instant): JsonObject = JsonObject(emptyMap())
}

private class FakeStore : IntegrationStateStore {
    private val enabled = mutableMapOf<String, Boolean>()
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
