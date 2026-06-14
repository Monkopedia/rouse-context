package com.rousecontext.app.push

import android.util.Log
import com.rousecontext.app.auth.FcmTokenProvider
import com.rousecontext.tunnel.TunnelClient
import com.rousecontext.work.ConnectPushReporter

/**
 * `google`-flavor [ConnectPushReporter]: fetches the current FCM registration
 * token and forwards it to the relay on each successful tunnel connect, so the
 * relay can deliver FCM wakeups (issue #476).
 *
 * The Firebase coupling sits behind the [FcmTokenProvider] seam
 * ([FirebaseFcmTokenProvider]); this class only orchestrates the per-connect
 * send so the shared [com.rousecontext.work.TunnelForegroundService] in `:work`
 * stays Firebase-free.
 */
class FcmConnectPushReporter(
    private val tunnelClient: TunnelClient,
    private val tokenProvider: FcmTokenProvider
) : ConnectPushReporter {

    override suspend fun reportOnConnect() {
        try {
            tunnelClient.sendFcmToken(tokenProvider.currentToken())
            Log.i(TAG, "Sent FCM token to relay")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send FCM token to relay", e)
        }
    }

    private companion object {
        const val TAG = "FcmConnectPushReporter"
    }
}
