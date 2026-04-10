package com.rousecontext.app.ui.viewmodels

import app.cash.turbine.test
import com.rousecontext.notifications.audit.AuditDao
import com.rousecontext.notifications.audit.AuditEntry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
            val state = awaitItem()
            assertTrue(state.groups.isEmpty())
            assertEquals("All providers", state.providerFilter)
            assertEquals("Today", state.dateFilter)
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
            vm.setProviderFilter("health")
            val state = awaitItem()
            assertEquals("health", state.providerFilter)
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
        val (start, end) = AuditHistoryViewModel.dateRangeFor("Today")
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
}
