package com.rousecontext.app.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.app.delivery.UnifiedPushBackgroundDelivery
import com.rousecontext.mcp.core.ProviderRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner

/**
 * Regression tests for issue #506: a UnifiedPush wake ANR'd [UnifiedPushReceiver]
 * because the connector's `MessagingReceiver.onReceive` calls `onMessage`
 * synchronously on the **main thread** with no `goAsync()`. `onMessage` routes
 * into `WakeDispatcher.dispatch` → `ProviderRegistry.awaitReadyBlocking(2000)`,
 * which is explicitly documented "MUST NOT be called from the main thread".
 *
 * The fix overrides [UnifiedPushReceiver.onReceive] to `goAsync()` and run the
 * connector's full receive sequence on a background dispatcher, finishing the
 * `PendingResult` only AFTER that blocking work completes (so the broadcast — and
 * with it the foreground-service-start exemption — stays alive across the
 * `startForegroundService` call inside the dispatch).
 *
 * Lives in `testFoss` because [UnifiedPushReceiver] is a `foss`-flavor class.
 */
@RunWith(RobolectricTestRunner::class)
class UnifiedPushReceiverThreadingTest {

    private lateinit var appScope: CoroutineScope

    @Before
    fun setUp() {
        runCatching { stopKoin() }
        // Production wires named("appScope") to a Dispatchers.Main scope; the
        // receiver itself moves the blocking work onto a background dispatcher
        // via withContext. A background-backed scope here keeps the test
        // deterministic without pumping the Robolectric main looper.
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        startKoin {
            modules(
                module {
                    single<CoroutineScope>(named("appScope")) { appScope }
                    single<ProviderRegistry> { mockk(relaxed = true) }
                    single { mockk<UnifiedPushBackgroundDelivery>(relaxed = true) }
                }
            )
        }
    }

    @After
    fun tearDown() {
        appScope.cancel()
        stopKoin()
    }

    @Test
    fun `onReceive runs connector delivery off the calling thread`() {
        val callingThread = Thread.currentThread()
        val deliveryThread = AtomicReference<Thread>()
        val deliveryEntered = CountDownLatch(1)

        val receiver = object : UnifiedPushReceiver() {
            override fun acquireAsyncResult(): BroadcastReceiver.PendingResult? = null

            override fun runConnectorReceive(context: Context, intent: Intent) {
                deliveryThread.set(Thread.currentThread())
                deliveryEntered.countDown()
            }
        }

        receiver.onReceive(context, Intent())

        assertTrue(
            "the blocking connector delivery must run asynchronously",
            deliveryEntered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        )
        assertNotEquals(
            "the connector delivery (and its main-thread-forbidden " +
                "awaitReadyBlocking) must NOT run on the calling thread",
            callingThread,
            deliveryThread.get()
        )
    }

    @Test
    fun `onReceive finishes the pending result only after the blocking delivery completes`() {
        val releaseDelivery = CountDownLatch(1)
        val deliveryCompleted = AtomicBoolean(false)
        val finishedBeforeDeliveryCompleted = AtomicBoolean(false)
        val finished = CountDownLatch(1)

        val pendingResult = mockk<BroadcastReceiver.PendingResult>()
        every { pendingResult.finish() } answers {
            if (!deliveryCompleted.get()) {
                finishedBeforeDeliveryCompleted.set(true)
            }
            finished.countDown()
        }

        val deliveryEntered = CountDownLatch(1)
        val receiver = object : UnifiedPushReceiver() {
            override fun acquireAsyncResult(): BroadcastReceiver.PendingResult = pendingResult

            override fun runConnectorReceive(context: Context, intent: Intent) {
                deliveryEntered.countDown()
                // Mimic awaitReadyBlocking blocking the broadcast thread.
                releaseDelivery.await()
                deliveryCompleted.set(true)
            }
        }

        receiver.onReceive(context, Intent())

        // While the delivery is still blocked, the PendingResult must stay open
        // so the broadcast keeps its foreground-service-start exemption.
        assertTrue(deliveryEntered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        verify(exactly = 0) { pendingResult.finish() }

        releaseDelivery.countDown()

        assertTrue(
            "the PendingResult must be finished after the delivery completes",
            finished.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        )
        assertFalse(
            "finish() must be called only AFTER the blocking dispatch " +
                "(incl. startForegroundService) has run",
            finishedBeforeDeliveryCompleted.get()
        )
    }

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
