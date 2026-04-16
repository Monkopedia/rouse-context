package com.rousecontext.tunnel

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Unit tests for [RelayApiClient] that run against an in-process Ktor
 * [MockEngine]. These guard the wire contract (JSON field names, HTTP method,
 * path) against accidental regressions.
 */
class RelayApiClientTest {

    @Test
    fun `requestSubdomain posts firebase_token and parses response shape`(): Unit = runBlocking {
        var capturedMethod: String? = null
        var capturedPath: String? = null
        var capturedBody: String? = null

        val httpClient = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    capturedMethod = request.method.value
                    capturedPath = request.url.encodedPath
                    capturedBody = (request.body as? TextContent)?.text
                        ?: (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.let {
                            String(it)
                        } ?: ""
                    respond(
                        content = ByteReadChannel(
                            """
                            {
                              "subdomain": "zephyr",
                              "base_domain": "rousecontext.com",
                              "fqdn": "zephyr.rousecontext.com",
                              "reservation_ttl_seconds": 600
                            }
                            """.trimIndent()
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        encodeDefaults = true
                    }
                )
            }
        }

        val api = RelayApiClient(baseUrl = "https://relay.example", httpClient = httpClient)
        val result = api.requestSubdomain(firebaseToken = "fake-firebase-token")

        assertTrue(result is RelayApiResult.Success)
        val body = result.data
        assertEquals("zephyr", body.subdomain)
        assertEquals("rousecontext.com", body.baseDomain)
        assertEquals("zephyr.rousecontext.com", body.fqdn)
        assertEquals(600L, body.reservationTtlSeconds)

        assertEquals("POST", capturedMethod)
        assertEquals("/request-subdomain", capturedPath)
        val parsed = Json.parseToJsonElement(capturedBody!!) as JsonObject
        assertEquals(
            "fake-firebase-token",
            parsed["firebase_token"]?.jsonPrimitive?.content,
            "request body must use snake_case firebase_token field"
        )

        httpClient.close()
    }

    @Test
    fun `requestSubdomain rate limited surfaces retry after`(): Unit = runBlocking {
        val httpClient = HttpClient(MockEngine) {
            engine {
                addHandler { _ ->
                    respond(
                        content = ByteReadChannel(""),
                        status = HttpStatusCode.TooManyRequests,
                        headers = headersOf(HttpHeaders.RetryAfter, "42")
                    )
                }
            }
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        val api = RelayApiClient(baseUrl = "https://relay.example", httpClient = httpClient)
        val result = api.requestSubdomain(firebaseToken = "fake-firebase-token")

        assertTrue(result is RelayApiResult.RateLimited)
        assertEquals(42L, result.retryAfterSeconds)

        httpClient.close()
    }

    @Test
    fun `requestSubdomain server error surfaces as Error with status code`(): Unit = runBlocking {
        val httpClient = HttpClient(MockEngine) {
            engine {
                addHandler { _ ->
                    respond(
                        content = ByteReadChannel("""{"error":"internal"}"""),
                        status = HttpStatusCode.InternalServerError,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        val api = RelayApiClient(baseUrl = "https://relay.example", httpClient = httpClient)
        val result = api.requestSubdomain(firebaseToken = "fake-firebase-token")

        assertTrue(result is RelayApiResult.Error)
        assertEquals(500, result.statusCode)

        httpClient.close()
    }
}
