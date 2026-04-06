package com.rousecontext.app.ui.viewmodels

import app.cash.turbine.test
import com.rousecontext.mcp.core.AuthorizationCodeManager
import com.rousecontext.mcp.core.InMemoryTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthorizationApprovalViewModelTest {

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
    fun `initial state has no pending requests`() = runTest(testDispatcher) {
        val manager = AuthorizationCodeManager(tokenStore = InMemoryTokenStore())
        val vm = AuthorizationApprovalViewModel(manager)

        vm.pendingRequests.test {
            val initial = awaitItem()
            assertTrue(initial.isEmpty())
        }
    }

    @Test
    fun `polls and shows new pending requests`() = runTest(testDispatcher) {
        val manager = AuthorizationCodeManager(tokenStore = InMemoryTokenStore())
        val vm = AuthorizationApprovalViewModel(manager)

        vm.pendingRequests.test {
            // Initial empty state
            assertEquals(0, awaitItem().size)

            // Create a request outside the VM
            manager.createRequest(
                clientId = "test-client",
                codeChallenge = "challenge",
                codeChallengeMethod = "S256",
                redirectUri = "http://localhost/callback",
                state = "state123",
                integration = "health"
            )

            // Advance past the poll interval
            advanceTimeBy(2_500)

            val requests = awaitItem()
            assertEquals(1, requests.size)
            assertEquals("health", requests[0].integration)
        }
    }

    @Test
    fun `approve removes request from pending list`() = runTest(testDispatcher) {
        val manager = AuthorizationCodeManager(tokenStore = InMemoryTokenStore())

        val request = manager.createRequest(
            clientId = "test-client",
            codeChallenge = "challenge",
            codeChallengeMethod = "S256",
            redirectUri = "http://localhost/callback",
            state = "state123",
            integration = "health"
        )

        val vm = AuthorizationApprovalViewModel(manager)

        vm.pendingRequests.test {
            // Let the initial poll run
            advanceTimeBy(2_500)
            // Skip until we see the request
            skipItems(1)
            val withRequest = awaitItem()
            assertEquals(1, withRequest.size)

            // Approve it
            vm.approve(request.displayCode)

            val afterApprove = awaitItem()
            assertTrue(afterApprove.isEmpty())
        }
    }

    @Test
    fun `deny removes request from pending list`() = runTest(testDispatcher) {
        val manager = AuthorizationCodeManager(tokenStore = InMemoryTokenStore())

        val request = manager.createRequest(
            clientId = "test-client",
            codeChallenge = "challenge",
            codeChallengeMethod = "S256",
            redirectUri = "http://localhost/callback",
            state = "state123",
            integration = "health"
        )

        val vm = AuthorizationApprovalViewModel(manager)

        vm.pendingRequests.test {
            // Let the initial poll run
            advanceTimeBy(2_500)
            skipItems(1)
            val withRequest = awaitItem()
            assertEquals(1, withRequest.size)

            // Deny it
            vm.deny(request.displayCode)

            val afterDeny = awaitItem()
            assertTrue(afterDeny.isEmpty())
        }
    }
}
