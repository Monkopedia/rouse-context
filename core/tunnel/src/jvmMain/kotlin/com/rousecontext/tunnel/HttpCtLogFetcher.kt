package com.rousecontext.tunnel

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.io.IOException

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
 */
class HttpCtLogFetcher(
    private val httpClient: HttpClient = RelayApiClient.createDefaultClient(),
    private val baseUrl: String = DEFAULT_CRT_SH_URL
) : CtLogFetcher {

    override suspend fun fetch(domain: String): String {
        val response = httpClient.get(baseUrl) {
            parameter("q", domain)
            parameter("output", "json")
        }
        if (!response.status.isSuccess()) {
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
            throw IOException("crt.sh returned HTTP ${response.status.value}$suffix")
        }
        return response.bodyAsText()
    }

    companion object {
        /** Public crt.sh JSON query endpoint. */
        const val DEFAULT_CRT_SH_URL = "https://crt.sh/"

        /**
         * Cap on how much of an error body we echo back into the exception
         * message. Short enough to stay readable in the notifications UI,
         * long enough to surface the "502 Bad Gateway" / "503" wording crt.sh
         * leads with.
         */
        private const val MAX_ERROR_BODY_PREVIEW = 120
    }
}
