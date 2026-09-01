package com.rousecontext.tunnel

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteChannel
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * What `RelayApiClient.executeRequest` does with a **cancellation** raised by
 * the request it guards (#646).
 *
 * ```
 * try {
 *     val response = block()                      // <- the request itself
 *     ...
 *     val body = try { response.bodyAsText() }    // <- see below
 *               catch (_: Exception) { "" }
 * } catch (e: Exception) { RelayApiResult.NetworkError(cause = e) }
 * ```
 *
 * The outer catch turned a cancelled request into a returned
 * [RelayApiResult.NetworkError], and this one reaches a caller with nothing
 * suspending in between. `OnboardingFlow.registerAndPersist` maps a
 * `NetworkError` to an [OnboardingResult] and `execute` folds and returns it
 * without another suspension point, so a cancelled onboarding hands the UI a
 * concrete "registration failed" outcome instead of unwinding -- work performed
 * after cancellation, which `.claude/rules/coroutines.md` forbids.
 *
 * ## Why the inner catch is deliberately left alone
 *
 * #646 lists `bodyAsText()` as a second site. It is not reachable by
 * cancellation, and the last test here is the measurement rather than an
 * argument: Ktor 3's `SaveBody` plugin (installed by default) drains the
 * response body inside `block()`, so by the time `bodyAsText()` runs it is
 * reading an in-memory buffer with no cancellable suspension point, and a body
 * that genuinely fails mid-read surfaces from `block()` as a
 * `ClosedByteChannelException` at the **outer** catch. A rethrow at the inner
 * catch would be exactly the dead guard #646's own comments warn about --
 * `WakeReconnectDecider:56` in the same family -- so it is documented in place
 * instead of added.
 */
class RelayApiClientCancellationTest {

    @Test
    fun `cancellation raised by the request propagates instead of becoming NetworkError`() =
        runBlocking {
            val client = clientOver(
                MockEngine { throw CancellationException("onboarding scope torn down") }
            )

            val thrown = assertFailsWith<Throwable> { client.requestSubdomain(TOKEN) }

            assertTrue(
                thrown is CancellationException,
                "a cancelled relay request must propagate cancellation, got " +
                    "${thrown.javaClass.name}: ${thrown.message}"
            )
        }

    @Test
    fun `a genuine transport failure is still reported as NetworkError`(): Unit = runBlocking {
        val client = clientOver(MockEngine { throw IOException("relay unreachable") })

        val result = client.requestSubdomain(TOKEN)

        assertIs<RelayApiResult.NetworkError>(result)
        assertEquals(
            "relay unreachable",
            result.cause.message,
            "the ordinary failure path must be unchanged"
        )
    }

    @Test
    fun `a body that fails mid-read surfaces from the request, not the body read`(): Unit =
        runBlocking {
            // Pins the reachability claim in the class kdoc: the inner
            // `bodyAsText()` catch never sees this failure, because SaveBody
            // already drained the channel inside `block()`. If a future Ktor
            // upgrade (or a client built without SaveBody) moves the read back
            // to `bodyAsText()`, this test flips to `RelayApiResult.Error` and
            // the inner catch becomes worth guarding.
            val client = clientOver(
                MockEngine {
                    respond(
                        content = ByteChannel().apply { cancel(IOException("reset mid-body")) },
                        status = HttpStatusCode.InternalServerError
                    )
                }
            )

            val result = client.requestSubdomain(TOKEN)

            assertIs<RelayApiResult.NetworkError>(result)
        }

    private fun clientOver(engine: MockEngine) = RelayApiClient(
        baseUrl = "http://relay.test",
        httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    )

    private companion object {
        const val TOKEN = "fake-firebase-id-token"
    }
}
