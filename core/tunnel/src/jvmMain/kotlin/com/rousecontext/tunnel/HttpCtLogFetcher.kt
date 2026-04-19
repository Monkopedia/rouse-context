package com.rousecontext.tunnel

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.io.IOException
import kotlinx.coroutines.delay

/**
 * Production [CtLogFetcher] that queries crt.sh over HTTPS for certificates
 * issued to the device's subdomain.
 *
 * crt.sh returns a JSON array of certificate entries when invoked with `output=json`.
 * Any I/O exception is propagated so [CtLogMonitor] can classify it as a Warning.
 *
 * Issue #227: crt.sh (maintained by Sectigo) periodically returns 502/503 HTML
 * error pages under load. We MUST check the HTTP status here and throw, so
 * [CtLogMonitor] classifies the outage as "Could not reach CT log service"
 * rather than letting the HTML body flow into the JSON parser and producing
 * a misleading "Malformed CT log response" warning.
 *
 * Issue #256: a single 5xx from crt.sh is almost always transient (the service
 * is community-run and flaps under load). Before surfacing a user-visible
 * warning we retry with exponential backoff: 1s, 3s, 9s -> total wall time
 * ~13s, bounded under the 30s budget for the security-check worker. 4xx stays
 * a hard failure because retrying a bad query will never succeed.
 */
class HttpCtLogFetcher(
    private val httpClient: HttpClient = RelayApiClient.createDefaultClient(),
    private val baseUrl: String = DEFAULT_CRT_SH_URL,
    private val retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS
) : CtLogFetcher {

    override suspend fun fetch(domain: String): String {
        // Total attempts = 1 initial + retryDelaysMs.size retries. Each retry
        // is preceded by the matching entry from [retryDelaysMs]. Cancellation
        // is respected via kotlinx.coroutines.delay, never Thread.sleep.
        var lastError: IOException? = null
        val totalAttempts = retryDelaysMs.size + 1
        for (attempt in 0 until totalAttempts) {
            if (attempt > 0) {
                delay(retryDelaysMs[attempt - 1])
            }
            try {
                return attemptFetch(domain)
            } catch (e: RetryableHttpException) {
                lastError = e.asIoException()
                // fall through to retry
            } catch (e: IOException) {
                // Network-level failures (connection reset, DNS flap, ...) are
                // transient by nature -- retry them on the same schedule as 5xx.
                lastError = e
            } catch (e: NonRetryableHttpException) {
                // 4xx indicates a query-shape bug; no amount of retry helps.
                throw e.asIoException()
            }
        }
        throw lastError ?: IOException("crt.sh request failed after $totalAttempts attempts")
    }

    private suspend fun attemptFetch(domain: String): String {
        val response = httpClient.get(baseUrl) {
            parameter("q", domain)
            parameter("output", "json")
        }
        if (response.status.isSuccess()) {
            return response.bodyAsText()
        }
        // Include a short body preview for diagnosability: crt.sh's HTML
        // outage pages typically lead with the error number and are useful
        // context in the worker log without bloating the notification text.
        val preview = runCatching { response.bodyAsText() }
            .getOrDefault("")
            .take(MAX_ERROR_BODY_PREVIEW)
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
        val suffix = if (preview.isEmpty()) "" else " body=\"$preview\""
        val statusCode = response.status.value
        val message = "crt.sh returned HTTP $statusCode$suffix"
        if (statusCode in 500..599) {
            throw RetryableHttpException(message)
        }
        throw NonRetryableHttpException(message)
    }

    private class RetryableHttpException(message: String) : RuntimeException(message) {
        fun asIoException(): IOException = IOException(message)
    }

    private class NonRetryableHttpException(message: String) : RuntimeException(message) {
        fun asIoException(): IOException = IOException(message)
    }

    companion object {
        /** Public crt.sh JSON query endpoint. */
        const val DEFAULT_CRT_SH_URL = "https://crt.sh/"

        /**
         * Backoff schedule between retry attempts: 1s, 3s, 9s. Four total
         * attempts (1 initial + 3 retries), ~13s total wall time, well under
         * the 30s per-check budget. See issue #256.
         */
        val DEFAULT_RETRY_DELAYS_MS: List<Long> = listOf(1_000L, 3_000L, 9_000L)

        /**
         * Cap on how much of an error body we echo back into the exception
         * message. Short enough to stay readable in the notifications UI,
         * long enough to surface the "502 Bad Gateway" / "503" wording crt.sh
         * leads with.
         */
        private const val MAX_ERROR_BODY_PREVIEW = 120
    }
}
