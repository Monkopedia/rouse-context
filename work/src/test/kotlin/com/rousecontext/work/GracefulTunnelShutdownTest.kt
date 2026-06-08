package com.rousecontext.work

import com.rousecontext.mcp.core.McpSession
import com.rousecontext.tunnel.TunnelClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the shared [gracefulTunnelShutdown] seam (issue #455).
 *
 * This is the single source of truth for the #446 teardown contract:
 * "close MCP sessions cleanly, bounded by a timeout, then drop the tunnel."
 */
class GracefulTunnelShutdownTest {

    @Test
    fun `calls shutdown then disconnect in order`() = kotlinx.coroutines.runBlocking {
        val mcpSession = mockk<McpSession>(relaxed = true)
        val tunnelClient = mockk<TunnelClient>(relaxed = true)

        gracefulTunnelShutdown(mcpSession, tunnelClient)

        coVerifyOrder {
            mcpSession.shutdown()
            tunnelClient.disconnect()
        }
    }

    @Test
    fun `disconnect still runs when shutdown hangs past the timeout`() =
        kotlinx.coroutines.runBlocking {
            val shutdownEntered = CompletableDeferred<Unit>()
            val mcpSession = mockk<McpSession>(relaxed = true)
            val tunnelClient = mockk<TunnelClient>(relaxed = true)

            // shutdown() never completes — it must be bounded by the timeout so
            // disconnect() still runs (withTimeoutOrNull contract).
            coEvery { mcpSession.shutdown() } coAnswers {
                shutdownEntered.complete(Unit)
                delay(GRACEFUL_SHUTDOWN_TIMEOUT_MS * 10)
            }

            gracefulTunnelShutdown(mcpSession, tunnelClient)

            assertTrue("shutdown should have been entered", shutdownEntered.isCompleted)
            coVerify { tunnelClient.disconnect() }
        }
}
