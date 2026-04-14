package com.rousecontext.work

import com.rousecontext.tunnel.TunnelClient
import com.rousecontext.tunnel.TunnelState
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Uses Robolectric so `android.util.Log` calls inside [FcmTokenRegistrar] resolve
 * to no-ops instead of throwing "not mocked" on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
class FcmTokenRegistrarTest {

    @Test
    fun `registerToken with tunnel CONNECTED forwards to sendFcmToken`() = runBlocking {
        val tunnelClient = mockk<TunnelClient>()
        every { tunnelClient.state } returns MutableStateFlow(TunnelState.CONNECTED)
        coEvery { tunnelClient.sendFcmToken(any()) } just Runs

        val registrar = FcmTokenRegistrar(tunnelClient)
        registrar.registerToken("token-abc")

        coVerify(exactly = 1) { tunnelClient.sendFcmToken("token-abc") }
    }

    @Test
    fun `registerToken with tunnel ACTIVE forwards to sendFcmToken`() = runBlocking {
        val tunnelClient = mockk<TunnelClient>()
        every { tunnelClient.state } returns MutableStateFlow(TunnelState.ACTIVE)
        coEvery { tunnelClient.sendFcmToken(any()) } just Runs

        val registrar = FcmTokenRegistrar(tunnelClient)
        registrar.registerToken("token-xyz")

        coVerify(exactly = 1) { tunnelClient.sendFcmToken("token-xyz") }
    }

    @Test
    fun `registerToken with tunnel DISCONNECTED does not call sendFcmToken`() = runBlocking {
        val tunnelClient = mockk<TunnelClient>()
        every { tunnelClient.state } returns MutableStateFlow(TunnelState.DISCONNECTED)

        val registrar = FcmTokenRegistrar(tunnelClient)
        registrar.registerToken("token-abc")

        coVerify(exactly = 0) { tunnelClient.sendFcmToken(any()) }
    }

    @Test
    fun `registerToken with tunnel CONNECTING does not call sendFcmToken`() = runBlocking {
        val tunnelClient = mockk<TunnelClient>()
        every { tunnelClient.state } returns MutableStateFlow(TunnelState.CONNECTING)

        val registrar = FcmTokenRegistrar(tunnelClient)
        registrar.registerToken("token-abc")

        coVerify(exactly = 0) { tunnelClient.sendFcmToken(any()) }
    }

    @Test
    fun `registerToken swallows exceptions from sendFcmToken`() = runBlocking {
        val tunnelClient = mockk<TunnelClient>()
        every { tunnelClient.state } returns MutableStateFlow(TunnelState.CONNECTED)
        coEvery { tunnelClient.sendFcmToken(any()) } throws RuntimeException("connection dropped")

        val registrar = FcmTokenRegistrar(tunnelClient)
        // Should not throw.
        registrar.registerToken("token-abc")

        coVerify(exactly = 1) { tunnelClient.sendFcmToken("token-abc") }
    }
}
