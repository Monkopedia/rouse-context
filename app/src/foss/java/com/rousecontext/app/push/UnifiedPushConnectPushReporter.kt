package com.rousecontext.app.push

import android.util.Log
import com.rousecontext.app.delivery.UnifiedPushBackgroundDelivery
import com.rousecontext.tunnel.TunnelClient
import com.rousecontext.work.ConnectPushReporter

/**
 * `foss`-flavor [ConnectPushReporter]: re-reports the persisted UnifiedPush
 * endpoint to the relay on each successful tunnel connect (issue #485).
 *
 * UnifiedPush endpoints can rotate at any time (a distributor may send a fresh
 * `NEW_ENDPOINT`). When that happens while the tunnel is DOWN,
 * [UnifiedPushBackgroundDelivery] persists the new endpoint but defers the relay
 * update (its `refreshEndpoint` path bails when not connected, trusting "a
 * future reconnect re-reports it"). Without a connect-time reporter that promise
 * was unkept on foss — the relay kept waking the STALE endpoint and the device
 * became unwakeable. This mirrors the `google` flavor's
 * [FcmConnectPushReporter], which re-sends its FCM token on every connect.
 *
 * The endpoint is read back from [UnifiedPushBackgroundDelivery.currentEndpoint]
 * — the same prefs entry the delivery writes — so there is a single source of
 * truth for the persisted value.
 */
class UnifiedPushConnectPushReporter(
    private val tunnelClient: TunnelClient,
    private val delivery: UnifiedPushBackgroundDelivery
) : ConnectPushReporter {

    override suspend fun reportOnConnect() {
        val endpoint = delivery.currentEndpoint()
        if (endpoint.isNullOrBlank()) {
            // No distributor picked yet (or unregistered): nothing to report.
            return
        }
        try {
            tunnelClient.sendPushEndpoint(
                kind = UnifiedPushBackgroundDelivery.PUSH_KIND,
                value = endpoint
            )
            Log.i(TAG, "Reported UnifiedPush endpoint to relay on connect")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to report UnifiedPush endpoint on connect", e)
        }
    }

    private companion object {
        const val TAG = "UnifiedPushConnectReporter"
    }
}
