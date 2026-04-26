package com.rousecontext.mcp.core

import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Regression test for issue #420 finding #5: if the embedded server fails to
 * start after `done` was reset (e.g. on a restart), `awaitClose()` would
 * previously block forever because `done` was only completed in `stop()`.
 * Post-fix, `start()` propagates the failure into `done` so `awaitClose()`
 * surfaces the start error instead of hanging.
 */
class McpSessionStartFailureTest {

    @Test
    fun `awaitClose surfaces start failure rather than hanging`() = runBlocking {
        val registry = InMemoryProviderRegistry()
        val tokenStore = InMemoryTokenStore()
        val boom = IOException("simulated start() failure")

        val session = McpSession(
            registry = registry,
            tokenStore = tokenStore,
            hostname = "test.rousecontext.com",
            integration = "health",
            serverStarter = { throw boom }
        )

        try {
            session.start(port = 0)
            fail("start() should have rethrown the simulated failure")
        } catch (e: IOException) {
            assertEquals(boom, e)
        }

        // Pre-fix: `awaitClose()` would suspend indefinitely. Post-fix:
        // `done` is completed exceptionally, so `await()` throws.
        val result = runCatching {
            withTimeout(2000L) { session.awaitClose() }
        }
        assertTrue(
            "awaitClose() should fail with the start error, not hang. result=$result",
            result.isFailure
        )
        // CompletableDeferred re-wraps the original cause when await() rethrows.
        // Walk the cause chain to find our marker exception.
        val thrown = result.exceptionOrNull()
        var matches = false
        var current: Throwable? = thrown
        while (current != null) {
            if (current === boom) {
                matches = true
                break
            }
            current = current.cause
        }
        assertTrue(
            "awaitClose() should surface the original start error in its cause chain, got $thrown",
            matches
        )
    }
}
