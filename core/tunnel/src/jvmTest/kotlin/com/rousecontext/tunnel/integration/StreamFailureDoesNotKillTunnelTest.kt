package com.rousecontext.tunnel.integration

import com.rousecontext.tunnel.MuxStream
import com.rousecontext.tunnel.TunnelClientImpl
import com.rousecontext.tunnel.TunnelState
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Regression guard for "stream-level failure does not kill the tunnel"
 * (issue #266, part of the batch-C scenarios tracked by #253).
 *
 * Scenario:
 *   1. Device connects to the relay, establishes a mux session.
 *   2. Relay pushes a synthetic inbound stream (via `POST /test/open-stream`)
 *      and the device observes it on `TunnelClient.incomingSessions`.
 *   3. Relay emits a synthetic `MuxFrame.Error` for that stream (via
 *      `POST /test/emit-stream-error`).
 *   4. The device MUST tear the stream down but MUST leave the tunnel state
 *      at [TunnelState.CONNECTED]; a second stream opened the same way
 *      afterwards MUST still be accepted.
 *
 * The `/test/open-stream` hook exists because driving the relay's real
 * passthrough path — an AI-client TLS handshake with SNI routing — is
 * blocked under Robolectric by Conscrypt's SNI suppression (issue #262).
 * Emitting a synthetic OPEN + ERROR pair is a narrow workaround that
 * exercises the device-side `MuxDemux` error-handling code path without
 * needing a real AI client.
 *
 * Related coverage:
 *   - `EndToEndSessionTest` (same module) covers OPEN/CLOSE/ERROR end-to-end
 *     through real SNI routing at the protocol layer.
 *   - This test pins the tunnel-state invariant specifically: a single
 *     stream's ERROR MUST NOT propagate up into a tunnel disconnect.
 */
@Tag("integration")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class StreamFailureDoesNotKillTunnelTest {

    companion object {
        private const val RELAY_HOSTNAME = "localhost"
        private const val DEVICE_SUBDOMAIN = "stream-failure"

        /** Accelerated keepalive so a mis-wired keepalive loop shows up fast. */
        private const val KEEPALIVE_INTERVAL_MS = 2_000L
        private const val KEEPALIVE_TIMEOUT_MS = 1_000L
        private const val KEEPALIVE_MAX_MISSES = 3

        /**
         * Wall-clock budget for the relay to register the mux session with
         * its `SessionRegistry` after the WebSocket upgrade. Bounded; if it
         * ever exceeds this the test fails loudly instead of flaking.
         */
        private const val SESSION_REGISTRATION_TIMEOUT_MS = 5_000L
        private const val POLL_MS = 100L

        private const val FIRST_STREAM_ID = 1001
        private const val SECOND_STREAM_ID = 1002

        /**
         * A stream id that is never OPENed on the device side; used by
         * [waitForRelaySession] as a harmless probe (the device silently
         * drops ERROR frames for unknown streams — see `MuxDemux.handleFrame`).
         */
        private const val PROBE_STREAM_ID: Int = 0x7FFF_FFFE

        /** How long to observe [TunnelState.CONNECTED] after the ERROR frame. */
        private const val POST_ERROR_OBSERVATION_MS = 2_000L
    }

    private lateinit var tempDir: File
    private lateinit var ca: TestCertificateAuthority
    private lateinit var relayManager: TestRelayManager
    private var relayPort: Int = 0

    private val caCert: X509Certificate get() = ca.caCert
    private val deviceKeyStore: KeyStore get() = ca.deviceKeyStore

    @BeforeEach
    fun setUp() {
        val relayBinary = findRelayBinary()
        assumeTrue(
            relayBinary.exists() && relayBinary.canExecute(),
            "Relay binary not found. Build with: cd relay && cargo build --features test-mode"
        )

        tempDir = File.createTempFile("stream-failure-", "")
        tempDir.delete()
        tempDir.mkdirs()

        ca = TestCertificateAuthority(tempDir, RELAY_HOSTNAME, DEVICE_SUBDOMAIN)
        ca.generate()

        relayManager = TestRelayManager(
            tempDir = tempDir,
            relayHostname = RELAY_HOSTNAME,
            enableTestMode = true
        )
        relayPort = findFreePort()
        relayManager.start(relayPort)
    }

    @AfterEach
    fun tearDown() {
        if (::relayManager.isInitialized) {
            relayManager.stop()
        }
        if (::tempDir.isInitialized && tempDir.exists()) {
            tempDir.deleteRecursively()
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `stream ERROR frame does not disconnect tunnel and subsequent stream is accepted`() =
        runBlocking {
            val tunnelScope = CoroutineScope(Dispatchers.IO)
            val tunnelClient = connectTunnelClient(tunnelScope)
            try {
                assertEquals(
                    TunnelState.CONNECTED,
                    tunnelClient.state.value,
                    "Tunnel should be CONNECTED before injecting stream-level failure"
                )

                val firstStream = openSyntheticStream(
                    tunnelScope,
                    tunnelClient,
                    FIRST_STREAM_ID,
                    FIRST_STREAM_UID
                )
                val firstDrainJob = drainInBackground(tunnelScope, firstStream)
                // The OPEN frame above flipped the tunnel to ACTIVE. Wait for
                // that transition to land so the upcoming "no DISCONNECTED"
                // observation isn't confused by the brief CONNECTED->ACTIVE
                // blip that MuxDemux emits via stateFlow.drop(1).
                withTimeoutOrNull(ACTIVE_WAIT) {
                    tunnelClient.state.first { it == TunnelState.ACTIVE }
                }

                emitErrorOnFirstStream()

                // Core invariant under test: a stream-level ERROR frame MUST
                // NOT knock the tunnel into DISCONNECTING / DISCONNECTED. The
                // tunnel may briefly stay ACTIVE (if MuxDemux has not yet
                // decremented the stream count) and then flip back to
                // CONNECTED — both are acceptable. Anything else is the
                // regression.
                val neverDisconnected = stateNeverReachedDisconnectedFor(
                    tunnelClient,
                    POST_ERROR_OBSERVATION_MS
                )
                assertTrue(
                    neverDisconnected,
                    "Tunnel must not transition to DISCONNECTED after a stream-level " +
                        "ERROR frame; observed state=${tunnelClient.state.value}"
                )
                // Once MuxDemux has processed the ERROR the stream count
                // returns to zero and we should be back in CONNECTED.
                val backToConnected = withTimeoutOrNull(ACTIVE_WAIT) {
                    tunnelClient.state.first { it == TunnelState.CONNECTED }
                }
                assertEquals(
                    TunnelState.CONNECTED,
                    backToConnected,
                    "Tunnel should return to CONNECTED once the errored stream is torn down"
                )
                firstDrainJob.cancel()

                val secondStream = openSyntheticStream(
                    tunnelScope,
                    tunnelClient,
                    SECOND_STREAM_ID,
                    SECOND_STREAM_UID
                )
                assertEquals(SECOND_STREAM_UID, secondStream.id)
                assertTunnelReachesActiveThenCleansUp(tunnelClient, secondStream)
            } finally {
                runCatching { tunnelClient.disconnect() }
                tunnelScope.coroutineContext.cancelChildren()
            }
        }

    /**
     * Ask the relay to push a synthetic OPEN frame for [streamId] and wait
     * until [TunnelClient.incomingSessions] surfaces the matching stream.
     * The async/await dance pre-arms the collector before the OPEN is sent
     * so MuxDemux's emission cannot race ahead of us.
     */
    private suspend fun openSyntheticStream(
        scope: CoroutineScope,
        client: TunnelClientImpl,
        streamId: Int,
        expectedUid: UInt
    ): MuxStream {
        val deferred = scope.async(Dispatchers.IO) {
            withTimeout(STREAM_WAIT) {
                client.incomingSessions.first { it.id == expectedUid }
            }
        }
        val opened = relayManager.openStream(
            subdomain = DEVICE_SUBDOMAIN,
            streamId = streamId,
            sniHostname = "ai.test"
        )
        assertTrue(opened, "relay should have enqueued the OPEN frame for $streamId")
        return deferred.await()
    }

    private fun emitErrorOnFirstStream() {
        val sent = relayManager.emitStreamError(
            subdomain = DEVICE_SUBDOMAIN,
            streamId = FIRST_STREAM_ID,
            errorCode = TestRelayManager.STREAM_RESET_CODE,
            message = "synthetic stream reset"
        )
        assertTrue(sent, "relay should have enqueued the ERROR frame")
    }

    /**
     * Confirms the tunnel reaches ACTIVE while the second stream is live,
     * then closes it and waits for the back-transition to CONNECTED so the
     * finally-block disconnect doesn't race with stream-count bookkeeping.
     */
    private suspend fun assertTunnelReachesActiveThenCleansUp(
        client: TunnelClientImpl,
        openStream: MuxStream
    ) {
        val becameActive = withTimeoutOrNull(ACTIVE_WAIT) {
            client.state.first { it == TunnelState.ACTIVE }
        }
        assertEquals(
            TunnelState.ACTIVE,
            becameActive,
            "Tunnel should transition to ACTIVE while the second stream is open"
        )
        openStream.close()
        withTimeoutOrNull(ACTIVE_WAIT) {
            client.state.first { it == TunnelState.CONNECTED }
        }
    }

    /**
     * Returns true iff [TunnelClient.state] stays out of
     * [TunnelState.DISCONNECTING] / [TunnelState.DISCONNECTED] for the entire
     * [windowMs] window, polling at [POLL_MS] intervals. [TunnelState.CONNECTED]
     * and [TunnelState.ACTIVE] are both acceptable — the invariant under test
     * is specifically "the tunnel does not die", not "the tunnel does not
     * briefly hold a stream".
     */
    private suspend fun stateNeverReachedDisconnectedFor(
        client: TunnelClientImpl,
        windowMs: Long
    ): Boolean {
        val deadline = System.currentTimeMillis() + windowMs
        while (System.currentTimeMillis() < deadline) {
            val s = client.state.value
            if (s == TunnelState.DISCONNECTED || s == TunnelState.DISCONNECTING) {
                return false
            }
            delay(POLL_MS)
        }
        val finalState = client.state.value
        return finalState != TunnelState.DISCONNECTED &&
            finalState != TunnelState.DISCONNECTING
    }

    /**
     * Collect [MuxStream.incoming] in the background so its bounded channel
     * never blocks MuxDemux. Any IO / cancellation exception is swallowed —
     * the stream may be torn down by the ERROR frame, which is exactly the
     * behaviour under test.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun drainInBackground(scope: CoroutineScope, stream: MuxStream): Job =
        scope.launch(Dispatchers.IO) {
            try {
                stream.incoming.collect { /* drop */ }
            } catch (_: Exception) {
                // stream closed/errored — expected
            }
        }

    private suspend fun connectTunnelClient(scope: CoroutineScope): TunnelClientImpl {
        val sslContext = TestSslContexts.buildMtls(deviceKeyStore, caCert)
        val wsFactory = MtlsWebSocketFactory(sslContext)
        val client = TunnelClientImpl(
            scope = scope,
            webSocketFactory = wsFactory,
            keepaliveIntervalMillis = KEEPALIVE_INTERVAL_MS,
            keepaliveTimeoutMillis = KEEPALIVE_TIMEOUT_MS,
            keepaliveMaxMisses = KEEPALIVE_MAX_MISSES
        )
        val url = "wss://$RELAY_HOSTNAME:$relayPort/ws"
        client.connect(url)
        // Wait for the relay to finish registering the session with its
        // SessionRegistry — otherwise the first `/test/open-stream` would hit
        // the "subdomain not registered" path and surface as 404.
        waitForRelaySession()
        return client
    }

    /**
     * Poll a harmless `/test/emit-stream-error` admin call on an unused
     * stream id until the relay reports the session is registered (response
     * = `{"emitted": true}`). The ERROR frame is silently discarded by the
     * device because no stream matches the id, so this probe has no
     * observable side effect.
     */
    private suspend fun waitForRelaySession() {
        val deadline = System.currentTimeMillis() + SESSION_REGISTRATION_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val seen = relayManager.emitStreamError(
                subdomain = DEVICE_SUBDOMAIN,
                streamId = PROBE_STREAM_ID,
                errorCode = TestRelayManager.STREAM_RESET_CODE,
                message = ""
            )
            if (seen) return
            delay(POLL_MS)
        }
        error(
            "Relay did not register the mux session for '$DEVICE_SUBDOMAIN' within " +
                "${SESSION_REGISTRATION_TIMEOUT_MS}ms"
        )
    }
}

private val STREAM_WAIT: Duration = 10.seconds
private val ACTIVE_WAIT: Duration = 5.seconds

// UInt literals: the `u` suffix is the only form the current Kotlin/IR
// `const` evaluator accepts inside `.first { it.id == ... }` predicates
// without tripping an internal `toUInt(kotlin.Int)` lookup bug. Kept at
// file scope rather than `companion object` because the IR bug also
// fires when these are `const val UInt` in a companion object — the
// `@Suppress("MayBeConst")` here is the intentional trade-off (see
// issue #266 for the full context).
@Suppress("MayBeConst")
private val FIRST_STREAM_UID: UInt = 1001u

@Suppress("MayBeConst")
private val SECOND_STREAM_UID: UInt = 1002u
