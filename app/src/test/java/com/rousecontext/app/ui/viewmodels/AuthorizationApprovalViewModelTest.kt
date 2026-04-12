package com.rousecontext.app.ui.viewmodels

import android.app.NotificationManager
import app.cash.turbine.test
import com.rousecontext.app.ui.screens.AuthorizationApprovalUiState
import com.rousecontext.mcp.core.AuthorizationCodeManager
import com.rousecontext.mcp.core.InMemoryTokenStore
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
    private val mockNotificationManager: NotificationManager = mockk(relaxed = true)

    private val defaultRedirectUri = "http://localhost/callback"
    private val validCodeChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createManager(): AuthorizationCodeManager {
        val manager = AuthorizationCodeManager(tokenStore = InMemoryTokenStore())
        manager.registerClient(
            clientId = "test-client",
            clientName = "Test",
            redirectUris = listOf(defaultRedirectUri)
        )
        return manager
    }

    @Test
    fun `initial state has no pending requests`() = runTest(testDispatcher) {
        val manager = createManager()
        val vm = AuthorizationApprovalViewModel(manager, mockNotificationManager)

        vm.pendingRequests.test {
            val initial = awaitItem()
            assertTrue(initial.isEmpty())
        }
    }

    @Test
    fun `reflects new pending requests without polling`() = runTest(testDispatcher) {
        val manager = createManager()
        val vm = AuthorizationApprovalViewModel(manager, mockNotificationManager)

        vm.pendingRequests.test {
            // Initial empty state
            assertEquals(0, awaitItem().size)

            // Create a request outside the VM
            manager.createRequest(
                clientId = "test-client",
                codeChallenge = validCodeChallenge,
                codeChallengeMethod = "S256",
                redirectUri = defaultRedirectUri,
                state = "state123",
                integration = "health"
            )

            val requests = awaitItem()
            assertEquals(1, requests.size)
            assertEquals("health", requests[0].integration)
        }
    }

    @Test
    fun `approve removes request from pending list`() = runTest(testDispatcher) {
        val manager = createManager()

        val request = manager.createRequest(
            clientId = "test-client",
            codeChallenge = validCodeChallenge,
            codeChallengeMethod = "S256",
            redirectUri = defaultRedirectUri,
            state = "state123",
            integration = "health"
        )

        val vm = AuthorizationApprovalViewModel(manager, mockNotificationManager)

        vm.pendingRequests.test {
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
        val manager = createManager()

        val request = manager.createRequest(
            clientId = "test-client",
            codeChallenge = validCodeChallenge,
            codeChallengeMethod = "S256",
            redirectUri = defaultRedirectUri,
            state = "state123",
            integration = "health"
        )

        val vm = AuthorizationApprovalViewModel(manager, mockNotificationManager)

        vm.pendingRequests.test {
            val withRequest = awaitItem()
            assertEquals(1, withRequest.size)

            // Deny it
            vm.deny(request.displayCode)

            val afterDeny = awaitItem()
            assertTrue(afterDeny.isEmpty())
        }
    }

    @Test
    fun `uiState transitions from Loading to Loaded on first emission`() = runTest(testDispatcher) {
        val manager = createManager()
        val vm = AuthorizationApprovalViewModel(manager, mockNotificationManager)

        // Before any coroutine runs, we're in Loading
        assertTrue(vm.uiState.value is AuthorizationApprovalUiState.Loading)

        // Drive the stateIn collector
        advanceUntilIdle()

        val loaded = vm.uiState.value
        assertTrue(
            "expected Loaded but was $loaded",
            loaded is AuthorizationApprovalUiState.Loaded
        )
        assertEquals(0, (loaded as AuthorizationApprovalUiState.Loaded).pendingRequests.size)
    }

    @Test
    fun `uiState reflects pending request items`() = runTest(testDispatcher) {
        val manager = createManager()
        val vm = AuthorizationApprovalViewModel(manager, mockNotificationManager)

        manager.createRequest(
            clientId = "test-client",
            codeChallenge = validCodeChallenge,
            codeChallengeMethod = "S256",
            redirectUri = defaultRedirectUri,
            state = "state123",
            integration = "health"
        )
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state is AuthorizationApprovalUiState.Loaded)
        val items = (state as AuthorizationApprovalUiState.Loaded).pendingRequests
        assertEquals(1, items.size)
        assertEquals("health", items[0].integration)
    }
}
