package com.rousecontext.work

import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.rousecontext.mcp.core.ProviderRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Receives FCM messages and dispatches them to the appropriate handler.
 *
 * - `type: "wake"` starts the [TunnelForegroundService] (only if integrations are enabled)
 * - `type: "renew"` enqueues a [CertRenewalWorker] via WorkManager (only if integrations are enabled)
 * - Unknown types are logged and ignored
 *
 * Wake and renewal requests are silently ignored when no integrations are enabled,
 * avoiding unnecessary foreground service starts and ACME cert quota usage.
 */
class FcmReceiver : FirebaseMessagingService() {

    private val tokenRegistrar: FcmTokenRegistrar by inject()
    private val providerRegistry: ProviderRegistry by inject()

    override fun onMessageReceived(message: RemoteMessage) {
        val action = FcmDispatch.resolve(message.data)
        Log.d(TAG, "FCM received: type=${message.data["type"]}, action=$action")

        when (action) {
            is FcmAction.StartService -> dispatchIfIntegrationsEnabled("wake") {
                startTunnelService()
            }
            is FcmAction.EnqueueRenewal -> dispatchIfIntegrationsEnabled("cert renewal") {
                enqueueCertRenewal()
            }
            is FcmAction.Ignore -> Log.w(TAG, "Ignoring unknown FCM type: ${action.type}")
        }
    }

    /**
     * Wait for the provider registry to load its initial enabled-state snapshot
     * (issue #414), then dispatch [action] only if at least one integration is
     * enabled. If the registry doesn't become ready within [REGISTRY_READY_TIMEOUT_MS],
     * drop the wake — that's safer than spinning up the tunnel against unknown state.
     *
     * Blocks the FCM background worker thread (NOT the main thread). FCM gives this
     * service ~10s before the system reclaims the wakelock, so a 2s wait is safe.
     */
    private fun dispatchIfIntegrationsEnabled(label: String, action: () -> Unit) {
        val ready = providerRegistry.awaitReadyBlocking(REGISTRY_READY_TIMEOUT_MS)
        if (!ready) {
            Log.w(
                TAG,
                "Ignoring $label: provider registry did not become ready within " +
                    "${REGISTRY_READY_TIMEOUT_MS}ms"
            )
            return
        }
        if (providerRegistry.enabledPaths().isEmpty()) {
            Log.i(TAG, "Ignoring $label: no integrations enabled")
            return
        }
        action()
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

    private fun startTunnelService() {
        val intent = Intent(this, TunnelForegroundService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun enqueueCertRenewal() {
        val request = OneTimeWorkRequestBuilder<CertRenewalWorker>().build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            CertRenewalWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    companion object {
        private const val TAG = "FcmReceiver"

        /**
         * How long to wait for the provider registry to load its first DataStore
         * emission before dropping a wake. DataStore reads are typically <100ms,
         * so 2s is generous. Issue #414.
         */
        internal const val REGISTRY_READY_TIMEOUT_MS = 2_000L
    }
}
