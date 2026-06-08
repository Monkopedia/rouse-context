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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Unit tests for [CertspotterCtLogFetcher], the fallback CT source used when
 * crt.sh is unreachable. The critical guarantee these tests lock in: Certspotter's
 * `issuer.name` string is byte-identical to crt.sh's `issuer_name` (same DN order +
 * comma-space), so the translated crt.sh-shaped JSON parses to the exact same
 * issuer set [CtLogMonitor] compares against -- zero normalization, zero false
 * Alert risk.
 */
class CertspotterCtLogFetcherTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `fetch translates certspotter issuers into crt sh shaped json`(): Unit = runBlocking {
        // A real-shaped Certspotter response: a JSON array of issuance objects,
        // each with a nested issuer{name} whose value is the full DN string.
        val certspotterJson = """
            [
              {
                "id": "1",
                "issuer": { "name": "C=US, O=Google Trust Services, CN=WR1" },
                "dns_names": ["abc123.rousecontext.com"]
              },
              {
                "id": "2",
                "issuer": { "name": "C=US, O=Let's Encrypt, CN=R13" },
                "dns_names": ["abc123.rousecontext.com"]
              }
            ]
        """.trimIndent()
        val httpClient = mockClient(certspotterJson, HttpStatusCode.OK)
        val fetcher = CertspotterCtLogFetcher(httpClient = httpClient)

        val result = fetcher.fetch("abc123.rousecontext.com")

        // Parse the returned string exactly as CtLogMonitor does and assert the
        // issuer_name set is byte-identical to the certspotter issuer.name values.
        val entries = json.decodeFromString(ListSerializer(CtLogEntry.serializer()), result)
        assertEquals(
            setOf(
                "C=US, O=Google Trust Services, CN=WR1",
                "C=US, O=Let's Encrypt, CN=R13"
            ),
            entries.map { it.issuerName }.toSet()
        )

        httpClient.close()
    }

    @Test
    fun `fetch returns empty array for empty certspotter response`(): Unit = runBlocking {
        val httpClient = mockClient("[]", HttpStatusCode.OK)
        val fetcher = CertspotterCtLogFetcher(httpClient = httpClient)

        val result = fetcher.fetch("abc123.rousecontext.com")

        val entries = json.decodeFromString(ListSerializer(CtLogEntry.serializer()), result)
        assertTrue(entries.isEmpty(), "Empty certspotter array must parse to empty list")

        httpClient.close()
    }

    @Test
    fun `fetch throws IOException on non-2xx`(): Unit = runBlocking {
        val httpClient = mockClient("upstream error", HttpStatusCode.BadGateway)
        val fetcher = CertspotterCtLogFetcher(httpClient = httpClient)

        val thrown = assertFailsWith<IOException> {
            fetcher.fetch("abc123.rousecontext.com")
        }
        assertTrue(
            thrown.message!!.contains("502"),
            "Status code must appear in exception message, was: ${thrown.message}"
        )

        httpClient.close()
    }

    private fun mockClient(body: String, status: HttpStatusCode): HttpClient =
        HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = ByteReadChannel(body),
                        status = status,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
        }
}
