package com.rousecontext.app.ui.viewmodels

import app.cash.turbine.test
import com.rousecontext.api.NotificationSettings
import com.rousecontext.api.NotificationSettingsProvider
import com.rousecontext.api.PostSessionMode
import com.rousecontext.app.ui.screens.AuditHistoryItem
import com.rousecontext.app.ui.screens.DateFilterOption
import com.rousecontext.app.ui.screens.ProviderFilterOption
import com.rousecontext.notifications.audit.AuditDao
import com.rousecontext.notifications.audit.AuditEntry
import com.rousecontext.notifications.audit.McpRequestDao
import com.rousecontext.notifications.audit.McpRequestEntry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuditHistoryViewModelTest {

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
    fun `initial state has empty groups and default filters`() = runTest(testDispatcher) {
        val auditDao = mockk<AuditDao> {
            every { observeByDateRange(any(), any(), any()) } returns flowOf(emptyList())
        }
        val vm = AuditHistoryViewModel(auditDao)

        vm.state.test {
            // First emission is the Loading initial value
            val loading = awaitItem()
            assertTrue(loading.isLoading)
            // Second emission is the loaded empty state
            val loaded = awaitItem()
            assertFalse(loaded.isLoading)
            assertTrue(loaded.groups.isEmpty())
            assertEquals(ProviderFilterOption.All, loaded.providerFilter)
            assertEquals(DateFilterOption.TODAY, loaded.dateFilter)
        }
    }

    @Test
    fun `state transitions to error when dao throws`() = runTest(testDispatcher) {
        val auditDao = mockk<AuditDao> {
            every { observeByDateRange(any(), any(), any()) } returns flow {
                error("db unavailable")
            }
        }
        val vm = AuditHistoryViewModel(auditDao)

        vm.state.test {
            val loading = awaitItem()
            assertTrue(loading.isLoading)
            val error = awaitItem()
            assertFalse(error.isLoading)
            assertNotNull(error.errorMessage)
            assertEquals("db unavailable", error.errorMessage)
        }
    }

    @Test
    fun `entries are grouped by date`() = runTest(testDispatcher) {
        val now = System.currentTimeMillis()
        val entries = listOf(
            createEntry(1, now - 1000, "get_steps"),
            createEntry(2, now - 2000, "get_heart_rate")
        )
        val auditDao = mockk<AuditDao> {
            every { observeByDateRange(any(), any(), any()) } returns flowOf(entries)
        }

        val vm = AuditHistoryViewModel(auditDao)

        vm.state.test {
            awaitItem() // initial
            val state = awaitItem()
            assertEquals(1, state.groups.size)
            assertEquals(2, state.groups[0].entries.size)
        }
    }

    @Test
    fun `changing provider filter queries with correct provider`() = runTest(testDispatcher) {
        val auditDao = mockk<AuditDao> {
            every { observeByDateRange(any(), any(), any()) } returns flowOf(emptyList())
        }

        val vm = AuditHistoryViewModel(auditDao)

        vm.state.test {
            awaitItem()
            vm.setProviderFilter(ProviderFilterOption.Specific("health"))
            val state = awaitItem()
            assertEquals(ProviderFilterOption.Specific("health"), state.providerFilter)
        }

        verify { auditDao.observeByDateRange(any(), any(), "health") }
    }

    @Test
    fun `clearHistory deletes all entries`() = runTest(testDispatcher) {
        val auditDao = mockk<AuditDao> {
            every { observeByDateRange(any(), any(), any()) } returns flowOf(emptyList())
            coEvery { deleteOlderThan(any()) } returns 5
        }

        val vm = AuditHistoryViewModel(auditDao)
        vm.clearHistory()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { auditDao.deleteOlderThan(any()) }
    }

    @Test
    fun `showAllMcpMessages off only surfaces tool call items`() = runTest(testDispatcher) {
        val now = System.currentTimeMillis()
        val auditDao = mockk<AuditDao> {
            every { observeByDateRange(any(), any(), any()) } returns flowOf(
                listOf(createEntry(1, now - 1000, "get_steps"))
            )
        }
        val mcpDao = mockk<McpRequestDao> {
            every { observeByDateRange(any(), any(), any()) } returns flowOf(
                listOf(createRequest(10, now - 500, "tools/list"))
            )
        }
        val provider = providerWithShowAll(false)

        val vm = AuditHistoryViewModel(
            auditDao = auditDao,
            mcpRequestDao = mcpDao,
            settingsProvider = provider
        )

        vm.state.test {
            awaitItem()
            val state = awaitItem()
            assertEquals(1, state.groups.size)
            assertEquals(1, state.groups[0].items.size)
            assertTrue(state.groups[0].items[0] is AuditHistoryItem.ToolCall)
            assertFalse(state.showAllMcpMessages)
        }
        // The request DAO is never queried when the toggle is off.
        verify(exactly = 0) { mcpDao.observeByDateRange(any(), any(), any()) }
    }

    @Test
    fun `showAllMcpMessages on interleaves requests with tool calls descending`() =
        runTest(testDispatcher) {
            val now = System.currentTimeMillis()
            val tool1 = createEntry(1, now - 3_000, "get_steps")
            val tool2 = createEntry(2, now - 1_000, "get_sleep")
            val req1 = createRequest(10, now - 2_000, "tools/list")
            val req2 = createRequest(11, now - 500, "initialize")

            val auditDao = mockk<AuditDao> {
                every {
                    observeByDateRange(any(), any(), any())
                } returns flowOf(listOf(tool1, tool2))
            }
            val mcpDao = mockk<McpRequestDao> {
                every {
                    observeByDateRange(any(), any(), any())
                } returns flowOf(listOf(req1, req2))
            }
            val provider = providerWithShowAll(true)

            val vm = AuditHistoryViewModel(
                auditDao = auditDao,
                mcpRequestDao = mcpDao,
                settingsProvider = provider
            )

            vm.state.test {
                awaitItem() // initial loading
                val state = awaitItem()
                assertTrue(state.showAllMcpMessages)
                assertEquals(1, state.groups.size)
                val items = state.groups[0].items
                assertEquals(4, items.size)
                // Expected timestamp-desc ordering: req2, tool2, req1, tool1
                assertTrue(items[0] is AuditHistoryItem.Request)
                assertEquals("initialize", (items[0] as AuditHistoryItem.Request).method)
                assertTrue(items[1] is AuditHistoryItem.ToolCall)
                assertEquals(
                    "get_sleep",
                    (items[1] as AuditHistoryItem.ToolCall).entry.toolName
                )
                assertTrue(items[2] is AuditHistoryItem.Request)
                assertEquals("tools/list", (items[2] as AuditHistoryItem.Request).method)
                assertTrue(items[3] is AuditHistoryItem.ToolCall)
                assertEquals(
                    "get_steps",
                    (items[3] as AuditHistoryItem.ToolCall).entry.toolName
                )
            }
        }

    @Test
    fun `groupByDate groups entries by calendar date`() {
        val dayOneMs = 1750032000000L // 2025-06-16 00:00 UTC
        val dayTwoMs = dayOneMs + 24 * 60 * 60 * 1000L
        val entries = listOf(
            createEntry(1, dayOneMs + 1000, "a"),
            createEntry(2, dayOneMs + 2000, "b"),
            createEntry(3, dayTwoMs + 1000, "c")
        )
        val groups = AuditHistoryViewModel.groupByDate(entries)
        assertEquals(2, groups.size)
    }

    @Test
    fun `dateRangeFor returns correct range for Today`() {
        val (start, end) = AuditHistoryViewModel.dateRangeFor(DateFilterOption.TODAY)
        assertTrue(start <= System.currentTimeMillis())
        assertTrue(end >= start)
    }

    private fun createEntry(id: Long, timestampMillis: Long, toolName: String): AuditEntry =
        AuditEntry(
            id = id,
            sessionId = "session1",
            toolName = toolName,
            provider = "health",
            timestampMillis = timestampMillis,
            durationMillis = 50,
            success = true
        )

    private fun createRequest(id: Long, timestampMillis: Long, method: String): McpRequestEntry =
        McpRequestEntry(
            id = id,
            sessionId = "session1",
            provider = "health",
            method = method,
            timestampMillis = timestampMillis,
            durationMillis = 12,
            resultBytes = 128
        )

    private fun providerWithShowAll(value: Boolean): NotificationSettingsProvider {
        val snapshot = NotificationSettings(
            postSessionMode = PostSessionMode.SUMMARY,
            notificationPermissionGranted = true,
            showAllMcpMessages = value
        )
        return mockk {
            coEvery { settings() } returns snapshot
            every { observeSettings() } returns flowOf(snapshot)
            coEvery { setPostSessionMode(any()) } returns Unit
            coEvery { setShowAllMcpMessages(any()) } returns Unit
        }
    }
}
