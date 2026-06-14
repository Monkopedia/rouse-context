package com.rousecontext.work

/**
 * Reports the device's push wake-target to the relay after each successful
 * tunnel connect.
 *
 * Flavor seam (issue #476): the `google` flavor binds a Firebase-backed impl
 * that fetches the current FCM token and forwards it with
 * [com.rousecontext.tunnel.TunnelClient.sendFcmToken]; the `foss` flavor reports
 * its UnifiedPush endpoint elsewhere (its `BackgroundDelivery`), so it binds a
 * no-op. Keeping the interface in `:work` lets [TunnelForegroundService] stay
 * flavor-agnostic and Firebase-free — the Firebase-coupled implementation lives
 * only in the `:app` `google` source set.
 */
fun interface ConnectPushReporter {
    /**
     * Invoked on the service's lifecycle scope right after a successful connect
     * (and after each successful reconnect). Implementations must swallow their
     * own transient failures — a failed token push is non-fatal and the next
     * wake re-syncs.
     */
    suspend fun reportOnConnect()
}
