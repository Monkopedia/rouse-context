package com.rousecontext.app.integration

import com.rousecontext.app.integration.TunnelTestSupport.awaitState
import com.rousecontext.app.integration.harness.AppIntegrationTestHarness
import com.rousecontext.app.push.UnifiedPushPayload
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
 * `foss` analogue of [ColdStartViaFcmTest] — closes the FCM/UnifiedPush CI
 * parity gap flagged in issue #481 (part of the #460 F-Droid epic).
 *
 * The `google` build's cold-start-via-FCM wake path has integration coverage
 * ([ColdStartViaFcmTest], [IdleTimeoutWakeTest], [RapidFcmWakesTest], ...), but
 * the `foss` build's UnifiedPush wake path had only a Roborazzi screenshot test
 * ([com.rousecontext.app.delivery.FossDeliveryMockTest]). This test exercises
 * the same "process is dead, boot from scratch, a wake brings it back" shape the
 * FCM test does, but through the `foss` UnifiedPush transport.
 *
 * Lives in `src/testFoss` so it only compiles + runs under the default (FOSS)
 * `:app:integrationTest`, where [UnifiedPushPayload] and the foss
 * `distributionModule` (keypair identity, zero Firebase) are on the classpath —
 * the same placement as [KeypairRoundTripIntegrationTest].
 *
 * # What this test covers (mirrors [ColdStartViaFcmTest])
 *
 *  1. A device is fully provisioned (subdomain + cert on disk).
 *  2. [AppIntegrationTestHarness.simulateColdStart] drops every in-memory
 *     singleton while keeping `context.filesDir`, the provisioned device
 *     keypair, and the relay subprocess — the "post-OOM kernel restart" state.
 *  3. An incoming UnifiedPush wake body (`{"type":"wake"}`, exactly what the
 *     relay's UnifiedPushClient POSTs to the device endpoint) is decoded by
 *     [UnifiedPushPayload.parse] — the foss transport analogue of the FCM data
 *     map — and asserted to route through the shared [FcmDispatch.resolve] to
 *     [FcmAction.StartService], the action that brings up
 *     [com.rousecontext.work.TunnelForegroundService].
 *  4. The re-booted Koin graph re-hydrates the persisted identity from disk
 *     without re-provisioning (#163 invariant).
 *  5. A fresh tunnel is driven to [TunnelState.CONNECTED] within a 10-second
 *     budget against the real `rouse-relay` subprocess.
 *  6. The relay-side `/ws` upgrade counter increases — proof the cold-booted
 *     tunnel actually reached the relay.
 *
 * # What is simulated vs. genuinely exercised
 *
 * Same boundary as [ColdStartViaFcmTest]: the Android `MessagingReceiver` →
 * `startForegroundService` → service-lifecycle leg is NOT driven here (it would
 * only re-test what unit tests already cover and what Robolectric's service
 * shadows cannot model as a real cold-start-via-intent). Genuinely exercised:
 * Koin tear-down/re-boot with only on-disk state surviving, the UnifiedPush
 * byte→map payload decode, disk→[CertificateStore] re-hydration, a fresh mTLS
 * TLS 1.3 handshake from a brand-new WebSocket, and a wall-clock bound on
 * cold-start-to-CONNECTED.
 *
 * Out of scope (per #481): the real ntfy.sh network hop and the deployed relay
 * binary's UnifiedPush POST — those stay manual/on-device. This is the
 * in-process/test-mode wake logic only.
 */
@RunWith(RobolectricTestRunner::class)
class ColdStartViaUnifiedPushTest {

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
    fun `foss cold-start via UnifiedPush wake reaches CONNECTED within 10s`() = runBlocking {
        // --- Phase 0: normal provisioning. Leaves disk populated with
        // subdomain + device cert + relay CA so the cold-start re-boot has
        // something to re-hydrate from. ---
        val subdomain = harness.provisionDevice()
        val wsCallsBeforeColdStart = harness.fixture.admin!!.wsCalls()

        // --- Phase 1: process death. Drop every in-memory singleton, keep
        // disk + relay subprocess + the provisioned device keypair running.
        // Same keystore-survives semantics as ColdStartViaFcmTest (#317). ---
        harness.simulateColdStart()

        // --- Phase 2: UnifiedPush wake decode + dispatch. The relay's
        // UnifiedPushClient POSTs the wake as the JSON body `{"type":"wake"}`.
        // UnifiedPushPayload.parse is the foss transport entry point (the
        // analogue of the FCM data map); assert it routes through the shared
        // FcmDispatch to StartService, which is what tells UnifiedPushReceiver
        // to bring up TunnelForegroundService. ---
        val wakeData = UnifiedPushPayload.parse(WAKE_PAYLOAD.toByteArray(Charsets.UTF_8))
        assertEquals(
            "UnifiedPush wake body must decode to a wake-typed payload",
            "wake",
            wakeData["type"]
        )
        assertEquals(
            "UnifiedPush wake payload must dispatch to StartService",
            FcmAction.StartService,
            FcmDispatch.resolve(wakeData)
        )

        // --- Phase 3: the re-booted Koin graph must see the persisted
        // identity intact (#163). ---
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
        // drive it to CONNECTED inside the 10s cold-start budget. ---
        val tunnel = TunnelTestSupport.buildTunnel(harness, tunnelScope)
        val url = TunnelTestSupport.tunnelUrl(harness)

        withTimeout(COLD_START_BUDGET) {
            tunnel.connect(url)
            tunnel.awaitState(TunnelState.CONNECTED, timeout = COLD_START_BUDGET)
        }

        // --- Phase 5: confirm the cold start actually reached the relay. ---
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
        /** The wake body the relay's UnifiedPushClient POSTs to the device endpoint. */
        const val WAKE_PAYLOAD = """{"type":"wake"}"""

        /**
         * Wall-clock budget for the full cold-start path (Koin reboot + disk
         * re-hydration + fresh mTLS + CONNECTED). Matches [ColdStartViaFcmTest].
         */
        val COLD_START_BUDGET = 10.seconds
    }
}
