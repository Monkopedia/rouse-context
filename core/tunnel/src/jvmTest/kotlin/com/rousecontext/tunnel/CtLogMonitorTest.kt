package com.rousecontext.tunnel

import kotlin.test.Test
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class CtLogMonitorTest {

    private val knownFingerprint = "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:" +
        "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89"

    @Test
    fun `crt sh returns only app-issued cert - verified`(): Unit = runBlocking {
        val fetcher = FakeCtLogFetcher(
            response = """[
                {"issuer_ca_id":123,"issuer_name":"C=US, O=Let's Encrypt, CN=R3",
                 "common_name":"abc123.rousecontext.com",
                 "name_value":"abc123.rousecontext.com",
                 "id":999,"entry_timestamp":"2025-01-01T00:00:00",
                 "not_before":"2025-01-01T00:00:00",
                 "not_after":"2025-04-01T00:00:00",
                 "serial_number":"abcdef0123456789"}
            ]"""
        )
        val store = SecurityCertificateStore(
            subdomain = "abc123",
            knownFingerprints = mutableSetOf(knownFingerprint)
        )
        val monitor = CtLogMonitor(
            certificateStore = store,
            ctLogFetcher = fetcher,
            expectedIssuers = setOf("C=US, O=Let's Encrypt, CN=R3"),
            baseDomain = "rousecontext.com"
        )

        val result = monitor.check()

        assertIs<SecurityCheckResult.Verified>(result)
    }

    @Test
    fun `crt sh returns unknown cert - alert`(): Unit = runBlocking {
        val fetcher = FakeCtLogFetcher(
            response = """[
                {"issuer_ca_id":123,"issuer_name":"C=US, O=Let's Encrypt, CN=R3",
                 "common_name":"abc123.rousecontext.com",
                 "name_value":"abc123.rousecontext.com",
                 "id":999,"entry_timestamp":"2025-01-01T00:00:00",
                 "not_before":"2025-01-01T00:00:00",
                 "not_after":"2025-04-01T00:00:00",
                 "serial_number":"abcdef0123456789"},
                {"issuer_ca_id":456,"issuer_name":"C=CN, O=Shady CA, CN=Evil",
                 "common_name":"abc123.rousecontext.com",
                 "name_value":"abc123.rousecontext.com",
                 "id":1000,"entry_timestamp":"2025-02-01T00:00:00",
                 "not_before":"2025-02-01T00:00:00",
                 "not_after":"2025-05-01T00:00:00",
                 "serial_number":"deadbeef01234567"}
            ]"""
        )
        val store = SecurityCertificateStore(subdomain = "abc123")
        val monitor = CtLogMonitor(
            certificateStore = store,
            ctLogFetcher = fetcher,
            expectedIssuers = setOf("C=US, O=Let's Encrypt, CN=R3"),
            baseDomain = "rousecontext.com"
        )

        val result = monitor.check()

        assertIs<SecurityCheckResult.Alert>(result)
    }

    @Test
    fun `crt sh unreachable - warning`(): Unit = runBlocking {
        val fetcher = FakeCtLogFetcher(throwOnFetch = true)
        val store = SecurityCertificateStore(subdomain = "abc123")
        val monitor = CtLogMonitor(
            certificateStore = store,
            ctLogFetcher = fetcher,
            expectedIssuers = setOf("C=US, O=Let's Encrypt, CN=R3"),
            baseDomain = "rousecontext.com"
        )

        val result = monitor.check()

        assertIs<SecurityCheckResult.Warning>(result)
    }

    @Test
    fun `empty response for new subdomain - verified`(): Unit = runBlocking {
        val fetcher = FakeCtLogFetcher(response = "[]")
        val store = SecurityCertificateStore(subdomain = "brandnew")
        val monitor = CtLogMonitor(
            certificateStore = store,
            ctLogFetcher = fetcher,
            expectedIssuers = setOf("C=US, O=Let's Encrypt, CN=R3"),
            baseDomain = "rousecontext.com"
        )

        val result = monitor.check()

        assertIs<SecurityCheckResult.Verified>(result)
    }

    @Test
    fun `malformed JSON handled gracefully`(): Unit = runBlocking {
        val fetcher = FakeCtLogFetcher(response = "this is not json {{{")
        val store = SecurityCertificateStore(subdomain = "abc123")
        val monitor = CtLogMonitor(
            certificateStore = store,
            ctLogFetcher = fetcher,
            expectedIssuers = setOf("C=US, O=Let's Encrypt, CN=R3"),
            baseDomain = "rousecontext.com"
        )

        val result = monitor.check()

        assertIs<SecurityCheckResult.Warning>(result)
    }
}

/** Fake fetcher for testing CtLogMonitor without real network calls. */
class FakeCtLogFetcher(
    private val response: String = "[]",
    private val throwOnFetch: Boolean = false
) : CtLogFetcher {
    override suspend fun fetch(domain: String): String {
        if (throwOnFetch) {
            throw java.io.IOException("Network unreachable")
        }
        return response
    }
}
