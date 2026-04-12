package com.rousecontext.tunnel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Abstraction for fetching CT log data. In production, hits crt.sh over HTTPS.
 * In tests, returns canned responses.
 */
interface CtLogFetcher {
    /** Fetch CT log entries for [domain] as raw JSON. */
    suspend fun fetch(domain: String): String
}

/**
 * Monitors Certificate Transparency logs via crt.sh to detect unauthorized
 * certificate issuance for this device's subdomain.
 *
 * Compares the issuers of all logged certificates against [expectedIssuers].
 * Any certificate from an unexpected issuer triggers an [SecurityCheckResult.Alert].
 *
 * Network errors or malformed responses produce [SecurityCheckResult.Warning]
 * since the check cannot be completed.
 */
class CtLogMonitor(
    private val certificateStore: CertificateStore,
    private val ctLogFetcher: CtLogFetcher,
    private val expectedIssuers: Set<String>,
    private val baseDomain: String = "rousecontext.com"
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Query crt.sh for certificates issued to this device's subdomain
     * and verify all come from expected issuers.
     */
    suspend fun check(): SecurityCheckResult {
        val subdomain = try {
            certificateStore.getSubdomain()
        } catch (e: Exception) {
            return SecurityCheckResult.Warning(
                "Could not retrieve subdomain: ${e.message}"
            )
        }

        if (subdomain == null) {
            return SecurityCheckResult.Warning("No subdomain configured")
        }

        val domain = "$subdomain.$baseDomain"
        val responseBody: String
        try {
            responseBody = ctLogFetcher.fetch(domain)
        } catch (e: Exception) {
            return SecurityCheckResult.Warning(
                "Could not reach CT log service: ${e.message}"
            )
        }

        val entries: List<CtLogEntry>
        try {
            entries = json.decodeFromString<List<CtLogEntry>>(responseBody)
        } catch (e: Exception) {
            return SecurityCheckResult.Warning(
                "Malformed CT log response: ${e.message}"
            )
        }

        if (entries.isEmpty()) {
            return SecurityCheckResult.Verified
        }

        val unexpectedEntries = entries.filter { it.issuerName !in expectedIssuers }
        return if (unexpectedEntries.isEmpty()) {
            SecurityCheckResult.Verified
        } else {
            val issuers = unexpectedEntries.map { it.issuerName }.distinct()
            SecurityCheckResult.Alert(
                "Unexpected certificate issuer(s) for $domain: $issuers"
            )
        }
    }
}

@Serializable
internal data class CtLogEntry(
    @SerialName("issuer_ca_id") val issuerCaId: Long = 0,
    @SerialName("issuer_name") val issuerName: String = "",
    @SerialName("common_name") val commonName: String = "",
    @SerialName("name_value") val nameValue: String = "",
    val id: Long = 0,
    @SerialName("entry_timestamp") val entryTimestamp: String = "",
    @SerialName("not_before") val notBefore: String = "",
    @SerialName("not_after") val notAfter: String = "",
    @SerialName("serial_number") val serialNumber: String = ""
)
