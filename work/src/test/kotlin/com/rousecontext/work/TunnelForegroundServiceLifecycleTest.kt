package com.rousecontext.work

import android.app.Notification
import android.os.Looper
import com.rousecontext.api.CrashReporter
import com.rousecontext.bridge.SessionHandler
import com.rousecontext.mcp.core.ProviderRegistry
import com.rousecontext.notifications.ForegroundNotifier
import com.rousecontext.notifications.NotificationChannels
import com.rousecontext.notifications.SessionSummaryNotifier
import com.rousecontext.tunnel.MuxStream
import com.rousecontext.tunnel.TunnelClient
import com.rousecontext.tunnel.TunnelError
import com.rousecontext.tunnel.TunnelState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
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
        }
        idleTimeoutManager = IdleTimeoutManager(
            timeoutMillis = Long.MAX_VALUE,
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
        startKoin {
            modules(
                module {
                    single<TunnelClient> { fakeTunnelClient }
                    single { mockk<SessionHandler>(relaxed = true) }
                    single { WakelockManager(FakeWakeLockHandle()) }
                    single { idleTimeoutManager }
                    single<ProviderRegistry> { providerRegistry }
                    single { mockk<SessionSummaryNotifier>(relaxed = true) }
                    single { mockk<SecurityCheckPreferences>(relaxed = true) }
                    single<String>(named("relayUrl")) { "wss://test.rousecontext.com" }
                    single<CrashReporter> { CrashReporter.NoOp }
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

        override suspend fun healthCheck(timeout: Duration): Boolean {
            healthCheckCalled = true
            return healthCheckResult
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
