package com.rousecontext.app.ui.viewmodels

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.notifications.audit.AuditDatabase
import com.rousecontext.notifications.audit.AuditEntry
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression test for GitHub issue #368.
 *
 * The [AuditHistoryViewModel] composes a Room [kotlinx.coroutines.flow.Flow]
 * through `stateIn(...)`. When the user visits the audit screen (empty),
 * navigates away, makes a tool call (which persists a new row via
 * `RoomAuditListener`), and then taps a per-tool-call notification that
 * deep-links back to the audit screen, the UI must not serve the stale
 * cached `groups = []` snapshot.
 *
 * With the previous `SharingStarted.WhileSubscribed(5_000L)` policy, the
 * upstream was kept alive for 5 seconds after the last subscriber left.
 * A re-subscribe that landed inside that grace window would receive the
 * stale empty-state value from `stateIn`'s replay cache even though the
 * DAO had been written to in the meantime (Room's invalidation-tracker
 * re-emission to a subscriberless shared flow is not guaranteed). The
 * fix is to tear down the upstream the moment the last subscriber leaves,
 * forcing a fresh DAO query on every re-subscribe.
 *
 * Exercises a real in-memory Room database so the test pins the actual
 * `stateIn` lifecycle semantics rather than mocking `observeByDateRange`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AuditHistoryViewModelRoomTest {

    private lateinit var database: AuditDatabase
    private lateinit var testScope: CoroutineScope

    @Before
    fun setUp() {
        // ViewModel's viewModelScope uses Dispatchers.Main.immediate. Routing
        // Main to the IO dispatcher lets Room's reactive Flow and the VM share
        // a real scheduler; Unconfined would trampoline emissions on the
        // calling thread and miss cross-thread invalidation-tracker wakeups.
        Dispatchers.setMain(Dispatchers.IO)
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Build the database directly so the query/transaction executor is a
        // real thread pool. Robolectric's default scheduling can leave Room's
        // Flow emissions unpumped, which would turn this test into a false
        // positive. We want Room to behave like it does on a real device.
        database = Room.inMemoryDatabaseBuilder(context, AuditDatabase::class.java)
            .setQueryExecutor(Executors.newSingleThreadExecutor())
            .setTransactionExecutor(Executors.newSingleThreadExecutor())
            .build()
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        testScope.coroutineContext[Job]?.cancel()
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `re-subscribing after a row insert reflects the new row without stale empty`() {
        runBlocking {
            val dao = database.auditDao()
            val vm = AuditHistoryViewModel(auditDao = dao)

            // Phase 1: first subscriber lands on an empty DB and sees groups = [].
            val firstLoaded = withTimeout(TIMEOUT_MS) {
                vm.state
                    .filter { !it.isLoading }
                    .first()
            }
            assertTrue(
                "Expected first emission to reflect empty DB, got ${firstLoaded.groups}",
                firstLoaded.groups.isEmpty()
            )

            // A no-op collector warms the shared flow so stateIn has seen at
            // least one live subscriber. Cancelling it simulates the user
            // leaving the audit screen.
            val warmupJob: Job = vm.state.onEach { /* observe */ }.launchIn(testScope)
            warmupJob.cancelAndJoin()

            // Phase 2: insert a row while the user is "away". This mirrors
            // RoomAuditListener.onToolCall firing during a live MCP session
            // that the user has since backgrounded.
            dao.insert(
                AuditEntry(
                    sessionId = "session-1",
                    toolName = "get_steps",
                    provider = "health",
                    timestampMillis = System.currentTimeMillis(),
                    durationMillis = 42L,
                    success = true,
                    clientLabel = "Claude Desktop"
                )
            )

            // Phase 3: re-subscribe. With a zero grace window the upstream
            // is rebuilt and the fresh DAO query returns the new row. With
            // the pre-fix five-second grace the stateIn served its cached
            // empty snapshot and this waits forever (bug #368).
            val reSubscribed = withTimeout(TIMEOUT_MS) {
                vm.state
                    .filter { !it.isLoading && it.groups.isNotEmpty() }
                    .first()
            }

            assertEquals(1, reSubscribed.groups.size)
            assertEquals(1, reSubscribed.groups[0].items.size)
        }
    }

    private companion object {
        // Deliberately longer than the previous STOP_TIMEOUT_MS (5s) so a
        // stuck grace window turns into a clear failure rather than a
        // flaky hang.
        const val TIMEOUT_MS = 10_000L
    }
}
