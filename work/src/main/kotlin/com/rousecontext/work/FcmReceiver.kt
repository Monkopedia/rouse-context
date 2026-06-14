package com.rousecontext.work

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.rousecontext.mcp.core.ProviderRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Receives FCM messages (google flavor) and routes them through the shared
 * [WakeDispatcher]:
 *
 * - `type: "wake"` starts the [TunnelForegroundService] (only if integrations are enabled)
 * - `type: "renew"` enqueues a [CertRenewalWorker] via WorkManager (only if integrations are enabled)
 * - Unknown types are logged and ignored
 *
 * Wake and renewal requests are silently ignored when no integrations are enabled,
 * avoiding unnecessary foreground service starts and ACME cert quota usage. The
 * `foss` flavor's UnifiedPush receiver shares the same [WakeDispatcher] logic
 * (issue #463).
 */
class FcmReceiver : FirebaseMessagingService() {

    private val tokenRegistrar: FcmTokenRegistrar by inject()
    private val providerRegistry: ProviderRegistry by inject()

    override fun onMessageReceived(message: RemoteMessage) {
        Log.d(TAG, "FCM received: type=${message.data["type"]}")
        WakeDispatcher(this, providerRegistry).dispatch(message.data)
    }

    override fun onNewToken(token: String) {
        Log.i(TAG, "New FCM token received")
        // Service-scoped: cancelled in onDestroy
        serviceScope.launch {
            try {
                tokenRegistrar.registerToken(token)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register FCM token", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    /** Coroutine scope tied to this service instance's lifetime. */
    private val serviceScope = CoroutineScope(SupervisorJob())

    companion object {
        private const val TAG = "FcmReceiver"
    }
}
