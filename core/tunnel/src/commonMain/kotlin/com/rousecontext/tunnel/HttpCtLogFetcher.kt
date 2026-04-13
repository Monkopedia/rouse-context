package com.rousecontext.tunnel

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText

/**
 * Production [CtLogFetcher] that queries crt.sh over HTTPS for certificates
 * issued to the device's subdomain.
 *
 * crt.sh returns a JSON array of certificate entries when invoked with `output=json`.
 * Any I/O exception is propagated so [CtLogMonitor] can classify it as a Warning.
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
        return response.bodyAsText()
    }

    companion object {
        /** Public crt.sh JSON query endpoint. */
        const val DEFAULT_CRT_SH_URL = "https://crt.sh/"
    }
}
