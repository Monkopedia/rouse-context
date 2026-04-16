package com.rousecontext.work

import com.rousecontext.tunnel.TunnelClient
import com.rousecontext.tunnel.TunnelError
import com.rousecontext.tunnel.TunnelState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeReconnectDeciderTest {

    private fun fakeClient(state: TunnelState, healthy: Boolean): TunnelClient {
        val client = mockk<TunnelClient>(relaxed = true)
        coEvery { client.state } returns MutableStateFlow(state)
        coEvery { client.errors } returns MutableSharedFlow<TunnelError>()
        coEvery { client.healthCheck(any()) } returns healthy
        return client
    }

    @Test
    fun `DISCONNECTED triggers Reconnect`() = runBlocking {
        val client = fakeClient(TunnelState.DISCONNECTED, healthy = false)
        val action = WakeReconnectDecider.decide(client, 2.seconds)
        assertTrue(action is WakeAction.Reconnect)
        assertEquals(false, (action as WakeAction.Reconnect).wasStale)
    }

    @Test
    fun `CONNECTING triggers AlreadyConnecting`() = runBlocking {
        val client = fakeClient(TunnelState.CONNECTING, healthy = true)
        val action = WakeReconnectDecider.decide(client, 2.seconds)
        assertEquals(WakeAction.AlreadyConnecting, action)
    }

    @Test
    fun `ACTIVE with healthy probe triggers Skip`() = runBlocking {
        val client = fakeClient(TunnelState.ACTIVE, healthy = true)
        val action = WakeReconnectDecider.decide(client, 2.seconds)
        assertEquals(WakeAction.Skip, action)
        coVerify(exactly = 1) { client.healthCheck(any()) }
    }

    @Test
    fun `ACTIVE with dead probe triggers Reconnect with wasStale=true`() = runBlocking {
        val client = fakeClient(TunnelState.ACTIVE, healthy = false)
        val action = WakeReconnectDecider.decide(client, 2.seconds)
        assertTrue(action is WakeAction.Reconnect)
        assertEquals(true, (action as WakeAction.Reconnect).wasStale)
    }

    @Test
    fun `ACTIVE with healthCheck throwing treats as dead`() = runBlocking {
        val client = mockk<TunnelClient>(relaxed = true)
        coEvery { client.state } returns MutableStateFlow(TunnelState.ACTIVE)
        coEvery { client.errors } returns MutableSharedFlow<TunnelError>()
        coEvery { client.healthCheck(any()) } throws RuntimeException("boom")

        val action = WakeReconnectDecider.decide(client, 2.seconds)
        assertTrue(action is WakeAction.Reconnect)
        assertEquals(true, (action as WakeAction.Reconnect).wasStale)
    }

    @Test
    fun `CONNECTED triggers Reconnect (not stale)`() = runBlocking {
        val client = fakeClient(TunnelState.CONNECTED, healthy = true)
        val action = WakeReconnectDecider.decide(client, 2.seconds)
        assertTrue(action is WakeAction.Reconnect)
        assertEquals(false, (action as WakeAction.Reconnect).wasStale)
        // Must NOT call healthCheck in non-ACTIVE state -- the CONNECTED
        // branch is explicit user action and we always refresh.
        coVerify(exactly = 0) { client.healthCheck(any()) }
    }
}
