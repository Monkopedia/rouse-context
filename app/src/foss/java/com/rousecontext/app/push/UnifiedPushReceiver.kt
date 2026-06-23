package com.rousecontext.app.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.rousecontext.app.delivery.UnifiedPushBackgroundDelivery
import com.rousecontext.mcp.core.ProviderRegistry
import com.rousecontext.work.WakeDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import org.unifiedpush.android.connector.MessagingReceiver

/**
 * UnifiedPush message receiver for the `foss` flavor (issue #463) — the
 * Firebase-free counterpart of the `google` flavor's
 * `com.rousecontext.app.push.FcmReceiver`.
 *
 * - [onMessage] routes the relay's `{"type":"wake"}` / `{"type":"renew"}`
 *   payload (delivered as the POST body by the relay's UnifiedPushClient)
 *   through the shared [WakeDispatcher], exactly as the FCM receiver does.
 * - [onNewEndpoint] reports the endpoint to the relay via
 *   [UnifiedPushBackgroundDelivery], which registers the device on the first
 *   endpoint (deferred activation) or refreshes it afterwards.
 * - [onRegistrationFailed] / [onUnregistered] reset activation so the degraded
 *   "set up a delivery app" Home banner reappears.
 *
 * ## Threading (issue #506)
 *
 * The UnifiedPush connector's [MessagingReceiver] is a plain [BroadcastReceiver]
 * whose `onReceive` calls [onMessage] **synchronously on the main thread** with
 * no `goAsync()`. [onMessage] routes into [WakeDispatcher.dispatch] →
 * `ProviderRegistry.awaitReadyBlocking(2000)`, which is explicitly documented
 * "MUST NOT be called from the main thread" — on a cold-start wake (registry not
 * yet ready) that blocks the main thread for up to 2s and ANRs the receiver.
 * (The `google` FCM path is immune: `FirebaseMessagingService.onMessageReceived`
 * already runs off the main thread.)
 *
 * The fix overrides [onReceive] to [goAsync] and runs the connector's full
 * receive sequence — `super.onReceive`, which dispatches to [onMessage] /
 * [onNewEndpoint] / etc. and then acknowledges — on a background dispatcher.
 * The broadcast's `PendingResult` is finished only **after** that work
 * completes, so the process keepalive AND the broadcast's temporary
 * foreground-service-start exemption survive across the `startForegroundService`
 * call that [WakeDispatcher] makes. Finishing early would drop both and make
 * `startForegroundService` throw `ForegroundServiceStartNotAllowedException`.
 */
open class UnifiedPushReceiver :
    MessagingReceiver(),
    KoinComponent {

    private val providerRegistry: ProviderRegistry by inject()
    private val delivery: UnifiedPushBackgroundDelivery by inject()
    private val appScope: CoroutineScope by inject(named("appScope"))

    /**
     * Run the connector's receive sequence off the main thread (issue #506).
     *
     * [goAsync] keeps the broadcast — and its foreground-service-start
     * exemption — alive; the actual dispatch (including the blocking
     * `awaitReadyBlocking` and `startForegroundService`) runs on
     * [Dispatchers.Default], and the `PendingResult` is finished only once that
     * has fully completed. The whole sequence stays well within the broadcast
     * ANR window (`awaitReadyBlocking` caps at 2s).
     */
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = acquireAsyncResult()
        appScope.launch {
            try {
                withContext(Dispatchers.Default) {
                    runConnectorReceive(context, intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "UnifiedPush delivery failed", e)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    /**
     * Detaches the broadcast from the main thread, returning a [PendingResult]
     * that must be [finished][BroadcastReceiver.PendingResult.finish] once the
     * off-thread work is done. Overridable so tests can drive the threading
     * without the framework's broadcast-dispatch machinery (`goAsync()` returns
     * `null` outside a real dispatch).
     */
    @VisibleForTesting
    internal open fun acquireAsyncResult(): BroadcastReceiver.PendingResult? = goAsync()

    /**
     * Runs the connector's full receive sequence (parse → [onMessage] /
     * [onNewEndpoint] / … → acknowledge, plus the connector's own 60s wakelock).
     * Overridable so tests can exercise [onReceive]'s threading without
     * constructing a connector-shaped intent.
     */
    @VisibleForTesting
    internal open fun runConnectorReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
    }

    override fun onMessage(context: Context, message: ByteArray, instance: String) {
        val data = UnifiedPushPayload.parse(message)
        Log.d(TAG, "UnifiedPush message received: type=${data["type"]}")
        WakeDispatcher(context, providerRegistry).dispatch(data)
    }

    override fun onNewEndpoint(context: Context, endpoint: String, instance: String) {
        Log.i(TAG, "UnifiedPush endpoint received")
        delivery.onEndpoint(endpoint)
    }

    override fun onRegistrationFailed(context: Context, instance: String) {
        delivery.onRegistrationFailed()
    }

    override fun onUnregistered(context: Context, instance: String) {
        delivery.onUnregistered()
    }

    companion object {
        private const val TAG = "UnifiedPushReceiver"
    }
}
