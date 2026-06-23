package com.rousecontext.work

import android.app.Notification
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Looper
import com.rousecontext.api.CrashReporter
import com.rousecontext.bridge.SessionHandler
import com.rousecontext.mcp.core.ProviderRegistry
import com.rousecontext.notifications.FgsLimitNotifier
import com.rousecontext.notifications.ForegroundNotifier
import com.rousecontext.notifications.NotificationChannels
import com.rousecontext.notifications.SessionSummaryNotifier
import com.rousecontext.tunnel.MuxStream
import com.rousecontext.tunnel.TunnelClient
import com.rousecontext.tunnel.TunnelError
import com.rousecontext.tunnel.TunnelState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlin.time.Duration
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tests [TunnelForegroundService] lifecycle transitions using Robolectric.
 *
 * Each test drives a [FakeTunnelClient]'s [MutableStateFlow] and verifies
 * the service's reactions (stop-self, reconnect suppression, etc.).
 *
 * The [WakeReconnectDecider] logic is NOT re-tested here -- that has its own
 * unit test. These tests focus on the service's reaction to the decider's
 * output and the state-flow observation loop.
 */
@RunWith(RobolectricTestRunner::class)
class TunnelForegroundServiceLifecycleTest {

    private lateinit var fakeTunnelClient: FakeTunnelClient
    private lateinit var idleTimeoutManager: IdleTimeoutManager
    private lateinit var providerRegistry: ProviderRegistry

    @Before
    fun setUp() {
        fakeTunnelClient = FakeTunnelClient()
        providerRegistry = mockk {
            every { enabledPaths() } returns setOf("health")
            every { providerForPath(any()) } returns null
            // Issue #414: TunnelForegroundService.onStartCommand now suspends on
            // awaitReady() before checking enabledPaths(). Default to "ready"
            // so existing lifecycle tests behave the same way.
            coEvery { awaitReady() } returns Unit
            every { awaitReadyBlocking(any()) } returns true
        }
        idleTimeoutManager = IdleTimeoutManager(
            timeoutProvider = { _ -> Long.MAX_VALUE },
            onTimeout = { /* no-op for tests */ }
        )

        // Mock notification objects to avoid resource resolution failures.
        // The :work module's Robolectric tests don't have merged resources
        // from :api, so ForegroundNotifier.build() would throw
        // Resources$NotFoundException for string resources defined in :api.
        mockkObject(ForegroundNotifier)
        every { ForegroundNotifier.build(any(), any()) } returns stubNotification()
        mockkObject(NotificationChannels)
        every { NotificationChannels.createAll(any()) } returns Unit

        runCatching { stopKoin() }
        startKoinWith(fakeTunnelClient)
    }

    /**
     * Start Koin with the lifecycle-test module, binding [client] as the
     * [TunnelClient]. Most tests use the shared [fakeTunnelClient]; the
     * reconcile test (#510) swaps in a [StuckCollectorTunnelClient].
     */
    private fun startKoinWith(client: TunnelClient) {
        startKoin {
            modules(
                module {
                    single<TunnelClient> { client }
                    single { mockk<SessionHandler>(relaxed = true) }
                    single { WakelockManager(FakeWakeLockHandle()) }
                    single { idleTimeoutManager }
                    single<ProviderRegistry> { providerRegistry }
                    single { mockk<SessionSummaryNotifier>(relaxed = true) }
                    single { mockk<SecurityCheckPreferences>(relaxed = true) }
                    single<String>(named("relayUrl")) { "wss://test.rousecontext.com" }
                    single<CrashReporter> { CrashReporter.NoOp }
                    // Per-connect push sync seam (issue #476). Production binds a
                    // flavor impl (FCM token push / UnifiedPush no-op); the
                    // lifecycle tests only need it to resolve.
                    single<ConnectPushReporter> { ConnectPushReporter { } }
                    // FGS type seam: production binds a flavor impl (google
                    // dataSync constant / foss specialUse-when-opted-in). Tests
                    // only need it to resolve; dataSync is the lifecycle default.
                    single<FgsTypeSelector> {
                        FgsTypeSelector {
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                        }
                    }
                }
            )
        }
    }

    @After
    fun tearDown() {
        stopKoin()
        unmockkObject(ForegroundNotifier)
        unmockkObject(NotificationChannels)
    }

    // -- Test 1: ACTIVE -> CONNECTED -> idle timeout -> DISCONNECTED stops service --

    @Test
    fun `transitions ACTIVE to CONNECTED to idle timeout DISCONNECTED stops service`() {
        fakeTunnelClient.stateFlow.value = TunnelState.DISCONNECTED

        val controller = Robolectric.buildService(TunnelForegroundService::class.java)
        val service = controller.get()
        controller.create()
        drainMain()
        controller.startCommand(0, 1)
        drainMain()

        // Simulate: tunnel connects
        fakeTunnelClient.stateFlow.value = TunnelState.CONNECTED
        drainMain()

        // Simulate: active session
        fakeTunnelClient.stateFlow.value = TunnelState.ACTIVE
        drainMain()

        // Simulate: session ends, back to CONNECTED
        fakeTunnelClient.stateFlow.value = TunnelState.CONNECTED
        drainMain()

        // Idle timeout fires: set the flag via reflection (private setter)
        setTimeoutFired(idleTimeoutManager, true)
        // Simulate: disconnect after idle timeout
        fakeTunnelClient.stateFlow.value = TunnelState.DISCONNECTED
        drainMain()

        assertTrue(
            "Service should stop itself after idle timeout disconnect",
            shadowOf(service).isStoppedBySelf
        )
    }

    // -- Test 2: FCM wake when ACTIVE with healthy probe forces reconnect (#243) --

    @Test
    fun `FCM wake when ACTIVE with healthy probe forces reconnect`() {
        // Start in ACTIVE state with a healthy probe.
        // Issue #243: an FCM wake means the relay needs a fresh connection,
        // even if the health check passes. The decider must return Reconnect.
        fakeTunnelClient.stateFlow.value = TunnelState.ACTIVE
        fakeTunnelClient.healthCheckResult = true

        val controller = Robolectric.buildService(TunnelForegroundService::class.java)
        controller.create()
        drainMain()

        val connectsBefore = fakeTunnelClient.connectCount

        // Simulate an FCM wake (new startCommand)
        controller.startCommand(0, 1)
        drainMain()

        assertTrue("healthCheck should have been called", fakeTunnelClient.healthCheckCalled)
        assertTrue(
            "disconnect should have been called to tear down stale tunnel",
            fakeTunnelClient.disconnectCalled
        )
        assertTrue(
            "Should call connect after FCM wake, even when probe is healthy",
            fakeTunnelClient.connectCount > connectsBefore
        )
    }

    // -- Test 3: FCM wake when ACTIVE with failing probe forces reconnect --

    @Test
    fun `FCM wake when ACTIVE with failing probe forces reconnect`() {
        // Start ACTIVE but with a dead health check
        fakeTunnelClient.stateFlow.value = TunnelState.ACTIVE
        fakeTunnelClient.healthCheckResult = false

        val controller = Robolectric.buildService(TunnelForegroundService::class.java)
        controller.create()
        drainMain()

        val connectsBefore = fakeTunnelClient.connectCount

        // Simulate an FCM wake
        controller.startCommand(0, 1)
        drainMain()

        // WakeReconnectDecider returns Reconnect(wasStale=true)
        assertTrue("healthCheck should have been called", fakeTunnelClient.healthCheckCalled)
        assertTrue(
            "disconnect should have been called for stale tunnel",
            fakeTunnelClient.disconnectCalled
        )
        assertTrue(
            "Should call connect after stale tunnel detected",
            fakeTunnelClient.connectCount > connectsBefore
        )
    }

    // -- Test 4: onStartCommand stopSelf when no integrations enabled --

    @Test
    fun `onStartCommand stopSelf when no integrations enabled`() {
        every { providerRegistry.enabledPaths() } returns emptySet()

        val controller = Robolectric.buildService(TunnelForegroundService::class.java)
        val service = controller.get()
        controller.create()
        drainMain()
        controller.startCommand(0, 1)
        drainMain()

        assertTrue(
            "Service should stop itself when no integrations are enabled",
            shadowOf(service).isStoppedBySelf
        )
    }

    // -- Test 5: intentionalDisconnect flag suppresses reconnect on disconnect --

    @Test
    fun `intentionalDisconnect flag suppresses reconnect on disconnect`() {
        fakeTunnelClient.stateFlow.value = TunnelState.DISCONNECTED

        val controller = Robolectric.buildService(TunnelForegroundService::class.java)
        val service = controller.get()
        controller.create()
        drainMain()
        controller.startCommand(0, 1)
        drainMain()

        // Simulate connected state
        fakeTunnelClient.stateFlow.value = TunnelState.CONNECTED
        drainMain()

        // Set intentionalDisconnect before the DISCONNECTED transition
        service.intentionalDisconnect = true
        fakeTunnelClient.stateFlow.value = TunnelState.DISCONNECTED
        drainMain()

        assertTrue(
            "Service should stop itself on intentional disconnect",
            shadowOf(service).isStoppedBySelf
        )
        // Only the initial connect call, no reconnect attempt
        assertTrue(
            "Should not attempt reconnect when intentionalDisconnect is set",
            fakeTunnelClient.connectCount <= 1
        )
    }

    // -- Test 6: startForeground is called immediately in onCreate (issue #325) --

    @Test
    fun `startForeground is called immediately in onCreate before main looper drains`() {
        fakeTunnelClient.stateFlow.value = TunnelState.DISCONNECTED

        val controller = Robolectric.buildService(TunnelForegroundService::class.java)
        val service = controller.get()

        // Call create() WITHOUT draining the main looper afterwards.
        // If startForeground is deferred to a coroutine or posted message,
        // the notification will not be present yet.
        controller.create()

        // Robolectric's shadow tracks the last notification passed to
        // startForeground(). It should be non-null immediately after
        // onCreate, proving that startForeground was called synchronously
        // — not deferred to a coroutine or handler post.
        val shadow = shadowOf(service)
        val fgNotification = shadow.lastForegroundNotification
        assertNotNull(
            "startForeground must be called synchronously in onCreate " +
                "before any coroutine or handler work runs (issue #325)",
            fgNotification
        )
    }

    // -- Test 7: onTimeout (run-time FGS budget exhaustion) stops the service (#450/#451) --

    @Test
    @Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
    fun `onTimeout stops service and posts FGS limit notification`() {
        // Android 15+ calls Service.onTimeout once the cumulative dataSync FGS
        // budget is exhausted while the service is running. If we don't stop
        // promptly the system kills us with ForegroundServiceDidNotStopInTime-
        // Exception (#450/#451). This mirrors the start-time budget handler.
        mockkObject(FgsLimitNotifier)
        every { FgsLimitNotifier.postLimitReachedNotification(any()) } returns Unit
        try {
            fakeTunnelClient.stateFlow.value = TunnelState.DISCONNECTED

            val controller = Robolectric.buildService(TunnelForegroundService::class.java)
            val service = controller.get()
            controller.create()
            drainMain()
            controller.startCommand(0, 1)
            drainMain()
            fakeTunnelClient.stateFlow.value = TunnelState.CONNECTED
            drainMain()

            // System signals the dataSync 6h budget is exhausted mid-run.
            service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            drainMain()

            assertTrue(
                "Service must stop itself when the FGS run-time timeout fires",
                shadowOf(service).isStoppedBySelf
            )
            assertTrue(
                "intentionalDisconnect must be set so the stop doesn't trigger a reconnect",
                service.intentionalDisconnect
            )
            verify { FgsLimitNotifier.postLimitReachedNotification(any()) }
        } finally {
            unmockkObject(FgsLimitNotifier)
        }
    }

    // -- Test 8: defensive reconcile fixes a stuck "Connecting…" (#510) --

    @Test
    fun `reconcile renders Connected when collector missed the CONNECTED transition`() {
        // #506/#510 repro: the socket is genuinely ESTABLISHED
        // (state.value == CONNECTED) but the Main-dispatcher observeStateChanges
        // collector was starved and only ever observed CONNECTING, so the
        // foreground notification is stuck on "Connecting…". The defensive
        // reconcile (#510) must read ground-truth state.value and render
        // "Connected", independent of that collector.
        val messages = mutableListOf<String>()
        every { ForegroundNotifier.build(any(), any()) } answers {
            messages.add(secondArg())
            stubNotification()
        }

        // No integrations enabled: onStartCommand reconciles immediately after
        // awaitReady(), then stops — so connectToRelay() never runs and the
        // reconcile hook is exercised in isolation from the connect machinery.
        every { providerRegistry.enabledPaths() } returns emptySet()

        val stuckClient = StuckCollectorTunnelClient(
            reportedValue = TunnelState.CONNECTED,
            emittedToCollectors = TunnelState.CONNECTING
        )
        stopKoin()
        startKoinWith(stuckClient)

        val controller = Robolectric.buildService(TunnelForegroundService::class.java)
        controller.create()
        drainMain()
        controller.startCommand(0, 1)
        drainMain()

        assertTrue(
            "Reconcile must render \"Connected\" once the socket is established, " +
                "even though the state collector only ever saw CONNECTING. Saw: $messages",
            messages.contains("Connected")
        )
    }

    // -- Helpers --

    /** Process all pending tasks on the main looper so lifecycleScope coroutines run. */
    private fun drainMain() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * Set [IdleTimeoutManager.timeoutFired] which has a private setter.
     * Tests need to simulate the timeout having fired without actually waiting.
     */
    private fun setTimeoutFired(manager: IdleTimeoutManager, value: Boolean) {
        val field = IdleTimeoutManager::class.java.getDeclaredField("timeoutFired")
        field.isAccessible = true
        field.set(manager, value)
    }

    /** Minimal notification stub for startForeground. */
    private fun stubNotification(): Notification = Notification.Builder(
        androidx.test.core.app.ApplicationProvider.getApplicationContext(),
        "test_channel"
    )
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .build()

    // -- Fakes --

    /**
     * Fake [TunnelClient] with a [MutableStateFlow] that tests can drive directly.
     * Records connect/disconnect/healthCheck calls for assertion.
     */
    private class FakeTunnelClient : TunnelClient {
        val stateFlow = MutableStateFlow(TunnelState.DISCONNECTED)
        private val _errors = MutableSharedFlow<TunnelError>()
        private val _incomingSessions = MutableSharedFlow<MuxStream>()

        var healthCheckResult: Boolean = true
        var healthCheckCalled: Boolean = false
        var connectCount: Int = 0
        var disconnectCalled: Boolean = false

        override val state: StateFlow<TunnelState> = stateFlow
        override val errors: SharedFlow<TunnelError> = _errors
        override val incomingSessions: Flow<MuxStream> = _incomingSessions

        override suspend fun connect(url: String) {
            connectCount++
            stateFlow.value = TunnelState.CONNECTED
        }

        override suspend fun disconnect() {
            disconnectCalled = true
            stateFlow.value = TunnelState.DISCONNECTED
        }

        override suspend fun sendFcmToken(token: String) {
            // no-op
        }

        override suspend fun sendPushEndpoint(kind: String, value: String) {
            // no-op for tests
        }

        override suspend fun healthCheck(timeout: Duration): Boolean {
            healthCheckCalled = true
            return healthCheckResult
        }
    }

    /**
     * A [TunnelClient] whose [state] reports one value via [StateFlow.value] but
     * only ever *emits* an older value to collectors — simulating a starved
     * observeStateChanges collector that never processed the CONNECTED
     * transition while the socket is genuinely established. See #506/#510.
     */
    private class StuckCollectorTunnelClient(
        reportedValue: TunnelState,
        emittedToCollectors: TunnelState
    ) : TunnelClient {
        override val state: StateFlow<TunnelState> =
            StuckStateFlow(reportedValue, emittedToCollectors)
        override val errors: SharedFlow<TunnelError> = MutableSharedFlow()
        override val incomingSessions: Flow<MuxStream> = MutableSharedFlow()
        override suspend fun connect(url: String) = Unit
        override suspend fun disconnect() = Unit
        override suspend fun sendFcmToken(token: String) = Unit
        override suspend fun sendPushEndpoint(kind: String, value: String) = Unit
        override suspend fun healthCheck(timeout: Duration): Boolean = true
    }

    /**
     * A [StateFlow] whose [value] (ground truth) diverges from what [collect]
     * replays to collectors. Collectors see only [emitted] and then suspend
     * forever, modelling a transition the main collector never processed.
     */
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private class StuckStateFlow(
        private val reported: TunnelState,
        private val emitted: TunnelState
    ) : StateFlow<TunnelState> {
        override val value: TunnelState = reported
        override val replayCache: List<TunnelState> = listOf(emitted)
        override suspend fun collect(collector: FlowCollector<TunnelState>): Nothing {
            collector.emit(emitted)
            awaitCancellation()
        }
    }

    private class FakeWakeLockHandle : WakeLockHandle {
        override var isHeld: Boolean = false
        override fun acquire() {
            isHeld = true
        }
        override fun release() {
            isHeld = false
        }
    }
}
