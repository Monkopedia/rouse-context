package com.rousecontext.tunnel

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Fallback [CtLogFetcher] that queries the Certspotter API for certificates
 * issued to the device's subdomain. Used by [CompositeCtLogFetcher] only after
 * the primary crt.sh source has already failed: crt.sh (community-run) returns
 * 502/503 for minutes at a time, outlasting [HttpCtLogFetcher]'s retry window,
 * which would otherwise surface a false "Could not reach CT log service" warning.
 *
 * Certspotter returns a JSON array of issuance objects, each with a nested
 * `issuer.name` DN string. VERIFIED against live data: that string is
 * byte-identical to crt.sh's `issuer_name` (same `C=US, O=..., CN=...` DN order
 * and comma-space separators), so no normalization is needed -- we map each
 * issuance's `issuer.name` straight into a crt.sh-shaped [CtLogEntry.issuerName].
 * The translated JSON is then parsed by [CtLogMonitor] exactly as a crt.sh
 * response would be, keeping the monitor and the crt.sh path 100% untouched.
 *
 * Error handling mirrors [HttpCtLogFetcher]: a non-2xx status throws an
 * [IOException]; network errors propagate. No internal retry -- the composite
 * only reaches this source after crt.sh failed, and we stay within the 30s
 * security-check worker budget.
 */
class CertspotterCtLogFetcher(
    private val httpClient: HttpClient = RelayApiClient.createDefaultClient(),
    private val baseUrl: String = DEFAULT_CERTSPOTTER_URL
) : CtLogFetcher {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetch(domain: String): String {
        val response = httpClient.get(baseUrl) {
            parameter("domain", domain)
            parameter("include_subdomains", "true")
            parameter("expand", "issuer")
        }
        if (!response.status.isSuccess()) {
            throw IOException("certspotter returned HTTP ${response.status.value}")
        }
        val issuances = json.decodeFromString<List<CertspotterIssuance>>(response.bodyAsText())
        // Map each issuance's issuer.name straight into a crt.sh-shaped entry.
        // The monitor only reads issuer_name, so the other fields stay default.
        val entries = issuances.map { CtLogEntry(issuerName = it.issuer.name) }
        return json.encodeToString(entries)
    }

    @Serializable
    private data class CertspotterIssuance(val issuer: CertspotterIssuer = CertspotterIssuer())

    @Serializable
    private data class CertspotterIssuer(@SerialName("name") val name: String = "")

    companion object {
        /** Public Certspotter issuances query endpoint. */
        const val DEFAULT_CERTSPOTTER_URL = "https://api.certspotter.com/v1/issuances"
    }
}
