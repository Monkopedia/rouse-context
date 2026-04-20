package com.rousecontext.app.integration

import com.rousecontext.app.integration.TunnelTestSupport.awaitState
import com.rousecontext.app.integration.harness.AppIntegrationTestHarness
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.TunnelState
import com.rousecontext.work.FcmAction
import com.rousecontext.work.FcmDispatch
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression guard for issue #317 — "cold-start via FCM wake actually works".
 *
 * Observed 2026-04-20: a freshly-installed APK with no running process received
 * an MCP request, the relay fired an FCM wake, and nothing came back. Every
 * existing resilience test ([IdleTimeoutWakeTest], [HalfOpenReconnectTest],
 * [RapidFcmWakesTest]) keeps a live [com.rousecontext.tunnel.TunnelClient] in
 * the JVM and simulates *post-connect* failures — none of them exercise the
 * "process is dead, boot from scratch" path.
 *
 * # What this test actually covers
 *
 * 1. A device is fully provisioned (subdomain + cert on disk).
 * 2. Koin is torn down and the harness coroutine scope cancelled —
 *    [AppIntegrationTestHarness.restartKoinPreservingState] already does this
 *    exactly to the shape issue #317's "simulate process death" helper
 *    called for: every in-memory singleton goes, `context.filesDir` stays, the
 *    relay subprocess stays. A re-provision / second-onboarding cannot
 *    accidentally satisfy the test because the relay keeps its Firestore record
 *    and the device cert keeps its subdomain — the only way back to CONNECTED
 *    is through the provisioned identity.
 * 3. An incoming FCM `type: "wake"` payload is dispatched through
 *    [FcmDispatch.resolve] and asserted to land on [FcmAction.StartService]. In
 *    production that action triggers
 *    [com.rousecontext.work.FcmReceiver.startTunnelService] which calls
 *    `startForegroundService` to bring up
 *    [com.rousecontext.work.TunnelForegroundService]. (See below for what's
 *    skipped in this simulation.)
 * 4. Koin re-boots from disk. This stands in for the Android framework invoking
 *    `Application.onCreate` when an FCM delivery brings a dead process back —
 *    the same observable state: fresh singletons, disk-preserved cert, fresh
 *    [com.rousecontext.tunnel.DeviceKeyManager]. We assert that the persisted
 *    subdomain round-trips through the re-booted [CertificateStore] without any
 *    re-provisioning — a non-negotiable invariant of the cold-start path (#163).
 * 5. A fresh [com.rousecontext.tunnel.TunnelClient] is built and driven to
 *    [TunnelState.CONNECTED] within a 10-second budget. This wall-clock bound
 *    is the core of issue #317: Claude's client-side request timeout appears to
 *    sit around 3-5 seconds, so a cold start that regresses past ~10 s would
 *    ship as "relay saw zero traffic" in the wild, which is what triggered this
 *    issue to be filed.
 * 6. The relay-side `/ws` upgrade counter increases — proof that the reboot
 *    actually reached the relay (as opposed to silently failing on disk).
 *
 * # What is *simulated* vs. what is *genuinely exercised*
 *
 * Genuinely exercised:
 *   - Koin graph tear-down and re-boot with only on-disk state surviving.
 *   - FCM payload parsing ([FcmDispatch.resolve]).
 *   - Disk → `CertificateStore` re-hydration on the fresh graph.
 *   - `DeviceKeyManager` re-instantiation (software-backed under Robolectric,
 *     but the *production* Android Keystore path performs an equivalent
 *     "re-obtain the secure-element handle" on process restart).
 *   - Fresh mTLS TLS 1.3 handshake from a brand-new WebSocket against the
 *     real `rouse-relay` subprocess.
 *   - Wall-clock bound on "cold-start to CONNECTED".
 *
 * Simulated / skipped (documented here so the next maintainer knows exactly
 * what a regression here does NOT catch):
 *   - `FcmReceiver.onMessageReceived` is NOT invoked. The production flow is
 *     `FirebaseMessagingService.onMessageReceived` → `FcmDispatch.resolve` →
 *     `ContextCompat.startForegroundService(this, intent)`. Robolectric's
 *     service shadows do not model the "cold-start-via-intent" lifecycle
 *     (process creation, `Application.onCreate` firing before the receiver is
 *     instantiated, manifest-declared service start). Invoking `FcmReceiver`
 *     under Robolectric would only test that
 *     `ContextCompat.startForegroundService` is called, which is already
 *     covered in [com.rousecontext.work.TunnelForegroundServiceLifecycleTest].
 *   - `TunnelForegroundService` is NOT driven end-to-end here. That service's
 *     lifecycle (stopSelf on empty providers, reconnect loop, idle timeout)
 *     has dedicated unit tests in
 *     [com.rousecontext.work.TunnelForegroundServiceLifecycleTest] with a
 *     [com.rousecontext.tunnel.TunnelClient] fake; here we want a real relay
 *     round-trip, which that service's Koin-injected
 *     [com.rousecontext.app.cert.LazyWebSocketFactory] can't reach (it knows
 *     nothing about the fixture's DNS override, test CA, or loopback port).
 *     Instead, we drive a fresh [com.rousecontext.tunnel.TunnelClientImpl] via
 *     [TunnelTestSupport.buildTunnel], which mirrors the production tunnel's
 *     construction signature and so exercises the same TLS + WebSocket code
 *     path the foreground service would use.
 *   - The first-request latency "product budget" metric the issue asks for
 *     (see issue body, "Also measure") is outside this test's scope — it
 *     belongs on the relay side and is tracked separately.
 *
 * Layer 2 (real-device `adb shell am force-stop` + HTTPS MCP call) is deferred
 * to follow-up issue #319.
 */
@RunWith(RobolectricTestRunner::class)
class ColdStartViaFcmTest {

    private val harness = AppIntegrationTestHarness()
    private lateinit var tunnelScope: CoroutineScope

    @Before
    fun setUp() {
        harness.start()
        tunnelScope = TunnelTestSupport.newTunnelScope()
    }

    @After
    fun tearDown() {
        tunnelScope.cancel()
        harness.stop()
    }

    @Test
    fun `cold-start path boots Koin from disk and reaches CONNECTED within 10s`() = runBlocking {
        // --- Phase 0: normal provisioning. Leaves disk populated with
        // subdomain + device cert + relay CA so the cold-start re-boot has
        // something to re-hydrate from. ---
        val subdomain = harness.provisionDevice()
        val wsCallsBeforeColdStart = harness.fixture.admin!!.wsCalls()

        // --- Phase 1: process death. Drop every in-memory singleton, keep
        // disk + relay subprocess + the provisioned device keypair running.
        // [simulateColdStart] mirrors what a post-OOM Android kernel restart
        // leaves for the app to re-boot out of: the Android Keystore's
        // `rouse_device_key` alias persists across process lifetimes (HAL
        // stores the key in secure storage), so the fresh process gets back
        // the SAME private key that signed the currently-on-disk client
        // cert. Using the stricter [restartKoinPreservingState] here would
        // break the mTLS handshake (fresh keypair's public key no longer
        // matches the provisioned cert's SPKI), which is not what a real
        // cold start observes. Issue #317. ---
        harness.simulateColdStart()

        // --- Phase 2: FCM wake dispatch. The FCM data payload the relay
        // sends for a wake is `{"type": "wake"}`. FcmDispatch is the pure
        // entry point; assert it routes to StartService, which is what tells
        // FcmReceiver to bring up TunnelForegroundService. ---
        val action = FcmDispatch.resolve(mapOf("type" to "wake"))
        assertEquals(
            "wake payload must dispatch to StartService",
            FcmAction.StartService,
            action
        )

        // --- Phase 3: the re-booted Koin graph must see the persisted
        // identity intact. If CertificateStore returned null here we'd be
        // heading for a re-onboarding, not a cold-start reconnect — #163's
        // invariant. ---
        val certStore: CertificateStore = harness.koin.get()
        assertEquals(
            "persisted subdomain must survive the cold-start reboot",
            subdomain,
            certStore.getSubdomain()
        )
        assertNotNull(
            "client cert must survive the cold-start reboot",
            certStore.getClientCertificate()
        )
        assertNotNull(
            "relay CA must survive the cold-start reboot",
            certStore.getRelayCaCert()
        )

        // --- Phase 4: stand up a fresh tunnel on the re-booted graph and
        // drive it to CONNECTED. The 10-second bound is the heart of issue
        // #317 — a real Android cold start should complete well inside this,
        // and going over it in CI flags a regression in the startup path
        // (disk read, mTLS handshake, mux negotiation). ---
        val tunnel = TunnelTestSupport.buildTunnel(harness, tunnelScope)
        val url = TunnelTestSupport.tunnelUrl(harness)

        withTimeout(COLD_START_BUDGET) {
            tunnel.connect(url)
            tunnel.awaitState(TunnelState.CONNECTED, timeout = COLD_START_BUDGET)
        }

        // --- Phase 5: confirm the cold start actually reached the relay.
        // wsCalls counts /ws upgrade requests since the relay started; if
        // the reboot silently stalled on disk we'd see no delta. ---
        val wsCallsAfter = harness.fixture.admin!!.wsCalls()
        assertTrue(
            "relay should have observed a new /ws upgrade from the cold-booted " +
                "tunnel ($wsCallsBeforeColdStart -> $wsCallsAfter)",
            wsCallsAfter > wsCallsBeforeColdStart
        )

        assertEquals(
            "tunnel must be CONNECTED after the cold-start reboot",
            TunnelState.CONNECTED,
            tunnel.state.value
        )
    }

    private companion object {
        /**
         * Wall-clock budget for the full cold-start path (Koin reboot + disk
         * re-hydration + fresh mTLS + CONNECTED). Real Android cold starts
         * typically finish in 2-4 s; 10 s keeps the test from being flaky on
         * loaded CI runners while still catching a genuine regression in the
         * startup path.
         */
        val COLD_START_BUDGET = 10.seconds
    }
}
