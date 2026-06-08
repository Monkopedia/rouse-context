package com.rousecontext.tunnel

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Unit tests for [CompositeCtLogFetcher]: try primary (crt.sh); on ANY failure
 * try the fallback (certspotter); if the fallback also fails, rethrow the PRIMARY's
 * exception so [CtLogMonitor]'s "Could not reach CT log service: <crt.sh message>"
 * warning stays meaningful.
 */
class CompositeCtLogFetcherTest {

    private fun fetcher(block: suspend (String) -> String): CtLogFetcher = object : CtLogFetcher {
        override suspend fun fetch(domain: String): String = block(domain)
    }

    @Test
    fun `primary success does not invoke fallback`(): Unit = runBlocking {
        var fallbackInvoked = false
        val composite = CompositeCtLogFetcher(
            primary = fetcher { "primary-result" },
            fallback = fetcher {
                fallbackInvoked = true
                "fallback-result"
            }
        )

        val result = composite.fetch("abc123.rousecontext.com")

        assertEquals("primary-result", result)
        assertFalse(fallbackInvoked, "Fallback must not be invoked when primary succeeds")
    }

    @Test
    fun `primary failure invokes fallback and returns fallback value`(): Unit = runBlocking {
        var fallbackInvoked = false
        val composite = CompositeCtLogFetcher(
            primary = fetcher { throw IOException("crt.sh returned HTTP 502") },
            fallback = fetcher {
                fallbackInvoked = true
                "fallback-result"
            }
        )

        val result = composite.fetch("abc123.rousecontext.com")

        assertTrue(fallbackInvoked, "Fallback must be invoked when primary fails")
        assertEquals("fallback-result", result)
    }

    @Test
    fun `both fail rethrows primary exception`(): Unit = runBlocking {
        val composite = CompositeCtLogFetcher(
            primary = fetcher { throw IOException("crt.sh returned HTTP 502") },
            fallback = fetcher { throw IOException("certspotter returned HTTP 503") }
        )

        val thrown = assertFailsWith<IOException> {
            composite.fetch("abc123.rousecontext.com")
        }
        assertEquals(
            "crt.sh returned HTTP 502",
            thrown.message,
            "When both sources fail, the PRIMARY (crt.sh) error must be surfaced"
        )
    }
}
