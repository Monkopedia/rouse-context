package com.rousecontext.tunnel

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Regression test for issue #420 finding #4: when `webSocketSession(url)`
 * throws before the session is bound, the handle's internal `sessionDeferred`
 * must be completed exceptionally so anyone awaiting `sendBinary` /
 * `sendText` does not hang forever.
 */
class KtorWebSocketFactoryFailBindTest {

    @Test
    fun `sendBinary fails fast when connect throws before bind`() = runBlocking {
        // Connecting to a port that nothing is listening on causes
        // `webSocketSession()` to throw inside the launched coroutine before
        // `handle.bind()` is called. Pre-fix: `sessionDeferred` was never
        // completed, so `handle.sendBinary()` hung on `sessionDeferred.await()`
        // until the surrounding scope was cancelled. Post-fix: the catch in
        // `connect()` calls `handle.failBind(e)` so awaiters surface the error
        // instead of hanging.
        val factory = KtorWebSocketFactory(scope = this)

        val onFailureCalled = CompletableDeferred<Throwable>()
        val listener = object : WebSocketListener {
            override fun onOpen() = Unit
            override fun onBinaryMessage(data: ByteArray) = Unit
            override fun onClosing(code: Int, reason: String) = Unit
            override fun onFailure(error: Throwable) {
                onFailureCalled.complete(error)
            }
        }

        val handle = factory.connect("ws://localhost:1/nope", listener)

        val result = runCatching {
            withTimeout(2000L) {
                handle.sendBinary(ByteArray(0))
            }
        }
        assertTrue(
            result.isFailure,
            "sendBinary should fail (not hang) when connect() never bound the session"
        )
        val cause = result.exceptionOrNull()
        assertTrue(
            cause !is TimeoutCancellationException,
            "sendBinary timed out instead of failing fast: $cause"
        )

        // Belt-and-braces: onFailure must have fired too.
        withTimeout(2000L) { onFailureCalled.await() }

        coroutineContext.cancelChildren()
    }
}
