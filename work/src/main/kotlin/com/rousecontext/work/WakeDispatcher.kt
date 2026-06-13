package com.rousecontext.work

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.rousecontext.mcp.core.ProviderRegistry

/**
 * Flavor-agnostic handler for an incoming wake/renew push payload.
 *
 * Extracted from [FcmReceiver] (issue #463) so the `foss` flavor's UnifiedPush
 * receiver can route through the exact same logic the `google` FCM receiver
 * uses — the relay sends the identical `{"type":"wake"}` / `{"type":"renew"}`
 * payloads over either transport, parsed by [FcmDispatch].
 *
 * - `type: "wake"` starts the [TunnelForegroundService] (only if integrations are enabled)
 * - `type: "renew"` enqueues a [CertRenewalWorker] (only if integrations are enabled)
 * - Unknown types are logged and ignored
 *
 * Wake/renew requests are silently ignored when no integrations are enabled,
 * avoiding unnecessary foreground-service starts and ACME cert quota usage.
 */
class WakeDispatcher(
    private val context: Context,
    private val providerRegistry: ProviderRegistry,
    private val registryReadyTimeoutMs: Long = REGISTRY_READY_TIMEOUT_MS
) {

    /** Resolve [data] to an action and execute it (gated on enabled integrations). */
    fun dispatch(data: Map<String, String>) {
        when (val action = FcmDispatch.resolve(data)) {
            is FcmAction.StartService -> dispatchIfIntegrationsEnabled("wake") {
                startTunnelService()
            }
            is FcmAction.EnqueueRenewal -> dispatchIfIntegrationsEnabled("cert renewal") {
                enqueueCertRenewal()
            }
            is FcmAction.Ignore -> Log.w(TAG, "Ignoring unknown push type: ${action.type}")
        }
    }

    /**
     * Wait for the provider registry to load its initial enabled-state snapshot
     * (issue #414), then dispatch [action] only if at least one integration is
     * enabled. If the registry doesn't become ready within
     * [registryReadyTimeoutMs], drop the wake — safer than spinning up the
     * tunnel against unknown state.
     *
     * Blocks the calling push worker/broadcast thread (NOT the main thread).
     */
    private fun dispatchIfIntegrationsEnabled(label: String, action: () -> Unit) {
        val ready = providerRegistry.awaitReadyBlocking(registryReadyTimeoutMs)
        if (!ready) {
            Log.w(
                TAG,
                "Ignoring $label: provider registry did not become ready within " +
                    "${registryReadyTimeoutMs}ms"
            )
            return
        }
        if (providerRegistry.enabledPaths().isEmpty()) {
            Log.i(TAG, "Ignoring $label: no integrations enabled")
            return
        }
        action()
    }

    private fun startTunnelService() {
        val intent = Intent(context, TunnelForegroundService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    private fun enqueueCertRenewal() {
        val request = OneTimeWorkRequestBuilder<CertRenewalWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            CertRenewalWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    companion object {
        private const val TAG = "WakeDispatcher"

        /**
         * How long to wait for the provider registry to load its first DataStore
         * emission before dropping a wake. DataStore reads are typically <100ms,
         * so 2s is generous. Issue #414.
         */
        const val REGISTRY_READY_TIMEOUT_MS = 2_000L
    }
}
