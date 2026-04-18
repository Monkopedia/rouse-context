package com.rousecontext.tunnel

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.io.IOException
import kotlin.test.Test
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
        val fetcher = HttpCtLogFetcher(httpClient = httpClient)

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
        val fetcher = HttpCtLogFetcher(httpClient = httpClient)

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
        val fetcher = HttpCtLogFetcher(httpClient = httpClient)
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
