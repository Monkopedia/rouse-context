package com.rousecontext.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.TestListenableWorkerBuilder
import com.rousecontext.notifications.SecurityCheckNotifier
import com.rousecontext.notifications.SecurityCheckNotifier.SecurityCheck
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.CtLogFetcher
import com.rousecontext.tunnel.CtLogMonitor
import com.rousecontext.tunnel.SecurityCheckResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The security check queries public Certificate Transparency log services
 * (crt.sh, Certspotter) with this device's hostname as the query value. An
 * F-Droid reviewer flagged that Settings offered only cadence choices and no
 * way to stop it. `Never` is that way.
 *
 * These tests pin the only thing that answers the complaint: with `Never`
 * selected, **no request leaves the device**. The assertion is therefore made
 * at the [CtLogFetcher] boundary — the frame that opens the socket — using a
 * real [CtLogMonitor] over a recording fetcher, not a stubbed
 * [SecurityCheckSource]. An implementation that left the worker running and
 * merely suppressed the stored result or greyed out the UI would still reach
 * the fetcher and would fail here.
 *
 * The gate is enforced in [SecurityCheckWorker] itself, not only in
 * [SecurityCheckScheduler], because cancelling the periodic work does not stop
 * the **one-time** run that `TunnelForegroundService.triggerOpportunisticSecurityCheck`
 * enqueues on tunnel connect. That path fires whenever the last check is older
 * than its staleness threshold — and under `Never` the last-check time never
 * advances, so it would fire on *every* connect. Cancelling only the periodic
 * work would therefore have made egress more frequent, not less.
 *
 * Both directions are covered on purpose: `Never` must stop the run, and an
 * interval must still run. Without the second, an implementation that never
 * checked at all would pass.
 */
@RunWith(RobolectricTestRunner::class)
class SecurityCheckNeverTest {

    private lateinit var context: Context
    private lateinit var prefs: SecurityCheckPreferences
    private lateinit var fetcher: RecordingCtLogFetcher
    private lateinit var selfCert: RecordingSelfCertSource
    private lateinit var notifier: SilentNotifier

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        prefs = SecurityCheckPreferences(context, minStreakIncrementIntervalMs = 0L)
        prefs.clearResults()
        prefs.setSecurityCheckEnabled(SecurityCheckPreferences.DEFAULT_SECURITY_CHECK_ENABLED)
        fetcher = RecordingCtLogFetcher()
        selfCert = RecordingSelfCertSource()
        notifier = SilentNotifier()
    }

    @Test
    fun `checks are enabled by default so shipped behaviour is unchanged`() = runBlocking {
        // `Never` is an added option, not a new default.
        assertTrue(SecurityCheckPreferences(context).securityCheckEnabled())
    }

    @Test
    fun `never - no request is made to any CT log service`() = runBlocking {
        prefs.setSecurityCheckEnabled(false)

        buildWorker().doWork()

        assertEquals(
            "Checks are set to Never: nothing may be sent to crt.sh / Certspotter. " +
                "Queried domains were ${fetcher.queriedDomains}",
            emptyList<String>(),
            fetcher.queriedDomains
        )
    }

    @Test
    fun `never - the local self-cert check does not run either`() = runBlocking {
        // Never means never: the interval governs the whole feature, not just
        // its network half. Losing the local check is the user's stated choice.
        prefs.setSecurityCheckEnabled(false)

        buildWorker().doWork()

        assertEquals(0, selfCert.invocations)
    }

    @Test
    fun `never - a stale result is not left behind reading as verified`() = runBlocking {
        // Seed a prior successful run. A gate that skipped the work but left
        // the old values in place would report "verified" forever, which is
        // the misleading half of "stale".
        prefs.recordCheck(
            lastCheckAt = 1_000L,
            selfCertResult = "verified",
            ctLogResult = "verified"
        )
        prefs.setSecurityCheckEnabled(false)

        buildWorker().doWork()

        assertEquals(SecurityCheckWorker.RESULT_DISABLED, prefs.selfCertResult())
        assertEquals(SecurityCheckWorker.RESULT_DISABLED, prefs.ctLogResult())
    }

    @Test
    fun `never - stale notifications are cleared without running the checks`() = runBlocking {
        // A warning or alert posted before the user chose Never would otherwise
        // sit in the shade forever: nothing runs again to cancel it. The
        // zero-invocation assertions are what stop this passing on unfixed
        // code, where two Verified results also produce two cancels.
        prefs.setSecurityCheckEnabled(false)

        buildWorker().doWork()

        assertEquals(0, selfCert.invocations)
        assertEquals(emptyList<String>(), fetcher.queriedDomains)
        assertTrue(
            "expected cancels for both checks, got ${notifier.cancels}",
            notifier.cancels.containsAll(
                listOf(SecurityCheck.SELF_CERT, SecurityCheck.CT_LOG)
            )
        )
        assertEquals(
            "A check the user switched off must not raise anything",
            emptyList<String>(),
            notifier.posts
        )
    }

    @Test
    fun `an interval is selected - the check still runs and still fetches`() = runBlocking {
        // Control direction. Without this, an implementation that disabled the
        // checks unconditionally would pass every assertion above.
        prefs.setSecurityCheckEnabled(true)

        buildWorker().doWork()

        assertEquals(listOf("dev1.rousecontext.com"), fetcher.queriedDomains)
        assertEquals(1, selfCert.invocations)
        assertEquals("verified", prefs.ctLogResult())
        assertEquals("verified", prefs.selfCertResult())
    }

    @Test
    fun `switching back from never restores a working check`() = runBlocking {
        prefs.setSecurityCheckEnabled(false)
        buildWorker().doWork()
        assertEquals(emptyList<String>(), fetcher.queriedDomains)

        prefs.setSecurityCheckEnabled(true)
        buildWorker().doWork()

        assertEquals(
            "Re-selecting an interval must resume checks — a one-way switch " +
                "would be a worse bug than the one being fixed",
            listOf("dev1.rousecontext.com"),
            fetcher.queriedDomains
        )
    }

    /**
     * Worker wired with a **real** [CtLogMonitor] so the assertion lands on the
     * fetcher, not on a stub standing in for it. The store returns a subdomain
     * and the monitor is otherwise fully configured, so an ungated run reaches
     * [CtLogFetcher.fetch] every time.
     */
    private fun buildWorker(): SecurityCheckWorker {
        val store = NeverTestStore(subdomain = "dev1")
        val worker = TestListenableWorkerBuilder<SecurityCheckWorker>(context).build()
        worker.selfCertVerifier = selfCert
        worker.ctLogMonitor = CtLogMonitorSource(
            CtLogMonitor(
                certificateStore = store,
                ctLogFetcher = fetcher,
                expectedIssuers = setOf("C=US, O=Google Trust Services LLC, CN=WE1"),
                baseDomain = "rousecontext.com"
            )
        )
        worker.notifier = notifier
        worker.preferences = prefs
        return worker
    }
}

/** Records every domain a CT log query was attempted for. */
private class RecordingCtLogFetcher : CtLogFetcher {
    val queriedDomains = mutableListOf<String>()

    override suspend fun fetch(domain: String): String {
        queriedDomains += domain
        return "[]"
    }
}

private class RecordingSelfCertSource : SecurityCheckSource {
    var invocations = 0
        private set

    override suspend fun check(): SecurityCheckResult {
        invocations++
        return SecurityCheckResult.Verified
    }
}

private class SilentNotifier : SecurityCheckNotifier {
    val posts = mutableListOf<String>()
    val cancels = mutableListOf<SecurityCheck>()

    override fun postAlert(check: SecurityCheck, reason: String) {
        posts += "alert:$check:$reason"
    }

    override fun postInfo(check: SecurityCheck, reason: String) {
        posts += "info:$check:$reason"
    }

    override fun cancel(check: SecurityCheck) {
        cancels += check
    }
}

/** Fully-onboarded store: a CT check against this WILL reach the fetcher. */
private class NeverTestStore(private val subdomain: String) : CertificateStore {
    override suspend fun getCertChain(): List<ByteArray>? = null
    override suspend fun getPrivateKeyBytes(): ByteArray? = null
    override suspend fun storeCertChain(chain: List<ByteArray>) = Unit
    override suspend fun getCertExpiry(): Long? = null
    override suspend fun getKnownFingerprints(): Set<String> = emptySet()
    override suspend fun storeFingerprint(fingerprint: String) = Unit
    override suspend fun hasFingerprintBootstrapMarker(): Boolean = false
    override suspend fun writeFingerprintBootstrapMarker() = Unit
    override suspend fun storeCertificate(pemChain: String) = Unit
    override suspend fun getCertificate(): String? = null
    override suspend fun storeClientCertificate(pemChain: String) = Unit
    override suspend fun getClientCertificate(): String? = null
    override suspend fun storeRelayCaCert(pem: String) = Unit
    override suspend fun getRelayCaCert(): String? = null
    override suspend fun storeSubdomain(subdomain: String) = Unit
    override suspend fun getSubdomain(): String = subdomain
    override suspend fun storeIntegrationSecrets(secrets: Map<String, String>) = Unit
    override suspend fun getIntegrationSecrets(): Map<String, String>? = null
    override suspend fun clear() = Unit
    override suspend fun clearCertificates() = Unit
}
