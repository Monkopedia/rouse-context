package com.rousecontext.tunnel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Regression test for issue #420 finding #6: if the WebSocket handshake
 * completes but the server immediately calls `onClosing` without first
 * calling `onOpen`, the `opened` deferred inside `connect()` was never
 * completed, leaving `connect()` hung on `opened.await()` until its scope
 * was cancelled. Post-fix, `onClosing` completes `opened` exceptionally on
 * that path so `connect()` surfaces an error promptly.
 */
class TunnelClientImplOnClosingDuringHandshakeTest {

    /**
     * A fake [WebSocketFactory] that synchronously calls [WebSocketListener.onClosing]
     * without a preceding [WebSocketListener.onOpen] and without throwing.
     */
    private class CloseDuringHandshakeFactory : WebSocketFactory {
        override fun connect(url: String, listener: WebSocketListener): WebSocketHandle {
            listener.onClosing(1011, "server policy")
            return object : WebSocketHandle {
                override suspend fun sendBinary(data: ByteArray): Boolean = false
                override suspend fun sendText(text: String): Boolean = false
                override suspend fun close(code: Int, reason: String) = Unit
            }
        }
    }

    @Test
    fun `connect surfaces error promptly when server closes during handshake`() = runBlocking {
        val client = TunnelClientImpl(this, CloseDuringHandshakeFactory())

        // Fast timeout: pre-fix, `opened.await()` would block until the
        // surrounding scope is cancelled, so this would consistently time out.
        // Post-fix, `onClosing` completes `opened` exceptionally so connect()
        // surfaces an error well within the budget.
        val budgetMillis = 500L
        val started = System.nanoTime()
        val result = runCatching {
            withTimeout(budgetMillis) { client.connect("ws://example.test/tunnel") }
        }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertTrue(
            result.isFailure,
            "connect() should fail when server closes before onOpen, but result was: $result"
        )
        val cause = result.exceptionOrNull()
        assertTrue(
            cause is TunnelError.WebSocketClosed,
            "connect() should surface TunnelError.WebSocketClosed, got $cause"
        )
        // Cause chain must reference the handshake-time close, not just any
        // TunnelError emitted by an unrelated path.
        assertTrue(
            (cause?.message ?: "").contains("handshake"),
            "TunnelError should mention the handshake-time close, got message: ${cause?.message}"
        )
        assertTrue(
            elapsedMillis < budgetMillis,
            "connect() should fail much faster than the timeout but took ${elapsedMillis}ms"
        )
        assertEquals(TunnelState.DISCONNECTED, client.state.value)

        coroutineContext.cancelChildren()
    }
}
