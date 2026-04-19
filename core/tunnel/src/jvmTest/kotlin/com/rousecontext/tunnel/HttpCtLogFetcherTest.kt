package com.rousecontext.tunnel

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Unit tests for [HttpCtLogFetcher]. These guard issue #227: crt.sh periodically
 * returns 502/503 HTML error pages, and before this fix the fetcher passed that
 * HTML straight to the JSON parser, producing a misleading "Malformed CT log
 * response" warning instead of the correct "Could not reach CT log service"
 * classification.
 */
class HttpCtLogFetcherTest {

    @Test
    fun `fetch throws IOException when crt sh returns 502 with HTML body`(): Unit = runBlocking {
        val htmlBody = "<html><body><h1>502 Bad Gateway</h1></body></html>"
        val httpClient = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = ByteReadChannel(htmlBody),
                        status = HttpStatusCode.BadGateway,
                        headers = headersOf(HttpHeaders.ContentType, "text/html")
                    )
                }
            }
        }
        val fetcher = HttpCtLogFetcher(
            httpClient = httpClient,
            retryDelaysMs = listOf(0L, 0L, 0L)
        )

        val thrown = assertFailsWith<IOException> {
            fetcher.fetch("abc123.rousecontext.com")
        }
        assertTrue(
            thrown.message!!.contains("502"),
            "Status code must appear in exception message for diagnosability, " +
                "was: ${thrown.message}"
        )

        httpClient.close()
    }

    @Test
    fun `fetch throws IOException when crt sh returns 503`(): Unit = runBlocking {
        val httpClient = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = ByteReadChannel("<html>service unavailable</html>"),
                        status = HttpStatusCode.ServiceUnavailable,
                        headers = headersOf(HttpHeaders.ContentType, "text/html")
                    )
                }
            }
        }
        val fetcher = HttpCtLogFetcher(
            httpClient = httpClient,
            retryDelaysMs = listOf(0L, 0L, 0L)
        )

        val thrown = assertFailsWith<IOException> {
            fetcher.fetch("abc123.rousecontext.com")
        }
        assertTrue(
            thrown.message!!.contains("503"),
            "Status code must appear in exception message, was: ${thrown.message}"
        )

        httpClient.close()
    }

    @Test
    fun `fetch returns body unchanged on 200 OK`(): Unit = runBlocking {
        val jsonBody = "[]"
        val httpClient = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = ByteReadChannel(jsonBody),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
        }
        val fetcher = HttpCtLogFetcher(httpClient = httpClient)

        val result = fetcher.fetch("abc123.rousecontext.com")
        kotlin.test.assertEquals(jsonBody, result)

        httpClient.close()
    }

    @Test
    fun `fetch retries on transient 502 and succeeds on third attempt`(): Unit = runBlocking {
        // Issue #256: crt.sh routinely 502s under load. A single failure is
        // nearly always transient, so the fetcher MUST retry on 5xx with
        // backoff before surfacing the failure as a warning.
        val attempts = AtomicInteger(0)
        val successBody = "[]"
        val httpClient = HttpClient(MockEngine) {
            engine {
                addHandler {
                    val n = attempts.incrementAndGet()
                    if (n < 3) {
                        respond(
                            content = ByteReadChannel("<html>502 Bad Gateway</html>"),
                            status = HttpStatusCode.BadGateway,
                            headers = headersOf(HttpHeaders.ContentType, "text/html")
                        )
                    } else {
                        respond(
                            content = ByteReadChannel(successBody),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                }
            }
        }
        // Zero-delay retry schedule keeps the test fast while still exercising
        // the retry loop. Production defaults are exercised by the companion
        // constant assertions in `retry schedule is bounded under 30s`.
        val fetcher = HttpCtLogFetcher(
            httpClient = httpClient,
            retryDelaysMs = listOf(0L, 0L, 0L)
        )

        val result = fetcher.fetch("abc123.rousecontext.com")

        assertEquals(successBody, result)
        assertEquals(3, attempts.get())
        httpClient.close()
    }

    @Test
    fun `fetch observes backoff between retries`(): Unit = runBlocking {
        val attempts = AtomicInteger(0)
        val httpClient = HttpClient(MockEngine) {
            engine {
                addHandler {
                    val n = attempts.incrementAndGet()
                    if (n < 2) {
                        respond(
                            content = ByteReadChannel("<html>502</html>"),
                            status = HttpStatusCode.BadGateway,
                            headers = headersOf(HttpHeaders.ContentType, "text/html")
                        )
                    } else {
                        respond(
                            content = ByteReadChannel("[]"),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                }
            }
        }
        // A single 150ms delay between attempt 1 and attempt 2.
        val fetcher = HttpCtLogFetcher(
            httpClient = httpClient,
            retryDelaysMs = listOf(150L, 150L, 150L)
        )

        val start = System.nanoTime()
        fetcher.fetch("abc123.rousecontext.com")
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertEquals(2, attempts.get())
        assertTrue(
            elapsedMs >= 140L,
            "Expected backoff >= 140ms, got ${elapsedMs}ms"
        )
        httpClient.close()
    }

    @Test
    fun `fetch throws after exhausting retries on persistent 502`(): Unit = runBlocking {
        val attempts = AtomicInteger(0)
        val httpClient = HttpClient(MockEngine) {
            engine {
                addHandler {
                    attempts.incrementAndGet()
                    respond(
                        content = ByteReadChannel("<html>502 Bad Gateway</html>"),
                        status = HttpStatusCode.BadGateway,
                        headers = headersOf(HttpHeaders.ContentType, "text/html")
                    )
                }
            }
        }
        val retryDelays = listOf(0L, 0L, 0L)
        val fetcher = HttpCtLogFetcher(
            httpClient = httpClient,
            retryDelaysMs = retryDelays
        )

        val thrown = assertFailsWith<IOException> {
            fetcher.fetch("abc123.rousecontext.com")
        }
        // 1 initial attempt + 3 retries = 4 total attempts.
        assertEquals(retryDelays.size + 1, attempts.get())
        assertTrue(
            thrown.message!!.contains("502"),
            "Final error must include the last-seen status, was: ${thrown.message}"
        )
        httpClient.close()
    }

    @Test
    fun `fetch does not retry on 4xx client error`(): Unit = runBlocking {
        // 4xx indicates a query-shape bug (e.g. malformed domain). Retrying
        // will never succeed, so we MUST fail fast instead of spending 13s
        // backing off before surfacing the same error.
        val attempts = AtomicInteger(0)
        val httpClient = HttpClient(MockEngine) {
            engine {
                addHandler {
                    attempts.incrementAndGet()
                    respond(
                        content = ByteReadChannel("bad request"),
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType, "text/plain")
                    )
                }
            }
        }
        val fetcher = HttpCtLogFetcher(
            httpClient = httpClient,
            retryDelaysMs = listOf(10_000L, 10_000L, 10_000L) // would make test slow if retried
        )

        val thrown = assertFailsWith<IOException> {
            fetcher.fetch("abc123.rousecontext.com")
        }
        assertEquals(1, attempts.get())
        assertTrue(
            thrown.message!!.contains("400"),
            "Expected 400 in message, was: ${thrown.message}"
        )
        httpClient.close()
    }

    @Test
    fun `fetch retries on network IOException and succeeds on recovery`(): Unit = runBlocking {
        val attempts = AtomicInteger(0)
        val httpClient = HttpClient(MockEngine) {
            engine {
                addHandler {
                    val n = attempts.incrementAndGet()
                    if (n < 2) {
                        throw IOException("connection reset by peer")
                    }
                    respond(
                        content = ByteReadChannel("[]"),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
        }
        val fetcher = HttpCtLogFetcher(
            httpClient = httpClient,
            retryDelaysMs = listOf(0L, 0L, 0L)
        )

        val result = fetcher.fetch("abc123.rousecontext.com")

        assertEquals("[]", result)
        assertEquals(2, attempts.get())
        httpClient.close()
    }

    @Test
    fun `default retry schedule stays within 30s total wall time`() {
        val total = HttpCtLogFetcher.DEFAULT_RETRY_DELAYS_MS.sum()
        assertTrue(
            total <= 30_000L,
            "Default retry schedule must not exceed 30s wall time, was ${total}ms"
        )
    }

    /**
     * Integration-level check (no real network): routes an HTML 5xx body through
     * the full [HttpCtLogFetcher] + [CtLogMonitor] stack and asserts the final
     * classification is the network-failure warning, not the JSON-parse warning.
     * This is the whole point of issue #227 at the worker level.
     */
    @Test
    fun `crt sh 502 HTML classified as network failure not malformed`(): Unit = runBlocking {
        val htmlBody = "<html><body>502 Bad Gateway</body></html>"
        val httpClient = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = ByteReadChannel(htmlBody),
                        status = HttpStatusCode.BadGateway,
                        headers = headersOf(HttpHeaders.ContentType, "text/html")
                    )
                }
            }
        }
        val fetcher = HttpCtLogFetcher(
            httpClient = httpClient,
            retryDelaysMs = listOf(0L, 0L, 0L)
        )
        val store = SecurityCertificateStore(subdomain = "abc123")
        val monitor = CtLogMonitor(
            certificateStore = store,
            ctLogFetcher = fetcher,
            expectedIssuers = setOf("C=US, O=Let's Encrypt, CN=R3"),
            baseDomain = "rousecontext.com"
        )

        val result = monitor.check()

        assertIs<SecurityCheckResult.Warning>(result)
        assertTrue(
            result.reason.contains("Could not reach CT log service"),
            "Expected network-failure wording, got: ${result.reason}"
        )
        assertTrue(
            !result.reason.contains("Malformed"),
            "Must NOT classify an HTTP 5xx as malformed, got: ${result.reason}"
        )

        httpClient.close()
    }
}
