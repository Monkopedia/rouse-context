package com.rousecontext.work

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.messaging.RemoteMessage
import com.rousecontext.mcp.core.McpServerProvider
import com.rousecontext.mcp.core.ProviderRegistry
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Regression tests for issue #414: cold-start FCM wakes were silently dropped because
 * [com.rousecontext.app.registry.IntegrationProviderRegistry] reported "no integrations
 * enabled" before its DataStore-backed flow had emitted its first value.
 *
 * The fix introduces [ProviderRegistry.awaitReadyBlocking] and routes FCM-receive
 * dispatches through it. These tests exercise the dispatch path without hitting
 * Firebase Cloud Messaging itself by constructing [FcmReceiver] directly under
 * Robolectric and feeding it a synthetic [RemoteMessage].
 */
@RunWith(RobolectricTestRunner::class)
class FcmReceiverTest {

    private lateinit var fakeRegistry: FakeProviderRegistry

    @Before
    fun setUp() {
        fakeRegistry = FakeProviderRegistry()
        runCatching { stopKoin() }
        startKoin {
            modules(
                module {
                    single<ProviderRegistry> { fakeRegistry }
                    // FcmReceiver also injects FcmTokenRegistrar but only uses
                    // it inside onNewToken, which these tests don't exercise.
                    single { mockk<FcmTokenRegistrar>(relaxed = true) }
                }
            )
        }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `wake dispatches to startService once registry becomes ready with enabled state`() {
        // Reproduces the cold-start race in issue #414: registry has not yet loaded
        // when the FCM wake arrives. The receiver must wait for the first emission
        // (with at least one integration enabled) and THEN start the service.
        //
        // Critical: BEFORE readiness, enabledPaths() returns emptySet() — exactly
        // the production race condition. A receiver that doesn't wait for ready
        // will see emptySet() and silently drop the wake.
        fakeRegistry.eventualEnabled = setOf("health")
        fakeRegistry.signalReadyAfter(50.millis)

        val receiver = buildReceiver()
        receiver.onMessageReceived(wakeMessage())

        val nextStarted = shadowOf(application).peekNextStartedService()
        assertNotNull(
            "Service must start once the registry's first emission lands",
            nextStarted
        )
        assertEquals(
            TunnelForegroundService::class.java.name,
            nextStarted!!.component!!.className
        )
    }

    @Test
    fun `wake dispatches synchronously when registry is already ready`() {
        fakeRegistry.eventualEnabled = setOf("health")
        fakeRegistry.signalReadyNow()

        val receiver = buildReceiver()
        receiver.onMessageReceived(wakeMessage())

        val nextStarted = shadowOf(application).peekNextStartedService()
        assertNotNull(nextStarted)
    }

    @Test
    fun `wake is dropped when registry becomes ready with empty integrations`() {
        // The user has disabled every integration. The registry loads, reports
        // emptySet(), and we should NOT start the service. This is the legitimate
        // post-fix "ignore wake" path (vs. the pre-fix race that ignored wakes
        // even when the user HAD integrations enabled on disk).
        fakeRegistry.eventualEnabled = emptySet()
        fakeRegistry.signalReadyNow()

        val receiver = buildReceiver()
        receiver.onMessageReceived(wakeMessage())

        assertNull(
            "Service must not start when no integrations are enabled",
            shadowOf(application).peekNextStartedService()
        )
    }

    @Test
    fun `wake is dropped when registry never becomes ready within the timeout`() {
        // If the registry hangs (DataStore stuck, etc.), drop the wake rather than
        // spin up the tunnel against unknown state. This is the safety fallback.
        fakeRegistry.eventualEnabled = setOf("health")
        // Do NOT signal ready — the latch will time out.

        // Override the constant via reflection would be invasive; instead exploit
        // the small explicit timeout in our fake to keep this test fast.
        fakeRegistry.blockingTimeoutOverride = 100L

        val receiver = buildReceiver()
        receiver.onMessageReceived(wakeMessage())

        assertNull(
            "Service must not start if the registry didn't become ready",
            shadowOf(application).peekNextStartedService()
        )
    }

    /** Build the receiver via Robolectric so its base-class context is wired. */
    private fun buildReceiver(): FcmReceiver =
        Robolectric.buildService(FcmReceiver::class.java).create().get()

    private val application: Application
        get() = ApplicationProvider.getApplicationContext()

    private fun wakeMessage(): RemoteMessage = RemoteMessage.Builder("test@fcm")
        .addData("type", "wake")
        .build()

    private val Int.millis: Long get() = toLong()

    /**
     * Hand-rolled fake of [ProviderRegistry] that mirrors the production race:
     * [enabledPaths] returns `emptySet()` until [eventualEnabled] is "loaded"
     * (signaled via [signalReadyAfter] or [signalReadyNow]).
     *
     * Pre-fix, [FcmReceiver] read `enabledPaths()` synchronously and saw the empty
     * set on every cold-start wake, silently dropping it. Post-fix, the receiver
     * blocks on [awaitReadyBlocking] first, so the test sees the loaded state.
     */
    private class FakeProviderRegistry : ProviderRegistry {
        /** What [enabledPaths] should return AFTER readiness. */
        var eventualEnabled: Set<String> = emptySet()
        private val ready = MutableStateFlow(false)
        private val latch = CountDownLatch(1)

        /**
         * Optional override for awaitReadyBlocking's caller-supplied timeout. Tests that
         * intentionally never signal ready use this to bound the test runtime, since
         * production FcmReceiver passes a 2s timeout.
         */
        var blockingTimeoutOverride: Long? = null

        fun signalReadyNow() {
            ready.value = true
            latch.countDown()
        }

        fun signalReadyAfter(delayMillis: Long) {
            Thread {
                Thread.sleep(delayMillis)
                ready.value = true
                latch.countDown()
            }.apply { isDaemon = true }.start()
        }

        override fun providerForPath(path: String): McpServerProvider? = null

        override fun enabledPaths(): Set<String> = if (ready.value) eventualEnabled else emptySet()

        override suspend fun awaitReady() {
            while (!ready.value) {
                delay(10)
            }
        }

        override fun awaitReadyBlocking(timeoutMs: Long): Boolean {
            val effective = blockingTimeoutOverride ?: timeoutMs
            return latch.await(effective, TimeUnit.MILLISECONDS)
        }
    }
}
