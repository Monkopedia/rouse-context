package com.rousecontext.work

import android.util.Log
import com.rousecontext.tunnel.TunnelClient
import com.rousecontext.tunnel.TunnelState
import kotlinx.coroutines.CancellationException

/**
 * Forwards refreshed FCM tokens to the relay over the active tunnel connection.
 *
 * When FCM rotates a device's token, this class pushes the new value to the relay
 * immediately if a tunnel session is live (state is [TunnelState.CONNECTED] or
 * [TunnelState.ACTIVE]). Otherwise it's a no-op: the tunnel pulls a fresh token
 * via `FirebaseMessaging.getInstance().token` on the next connect, so a disconnected
 * refresh is naturally covered by that path.
 *
 * The tunnel client reference is a Koin `single` with app-lifetime scope, so there
 * is no leak risk. If the connection state flips mid-send the underlying send may
 * throw; that's caught and logged — the pull-on-connect path will re-sync.
 */
class FcmTokenRegistrar(private val tunnelClient: TunnelClient) {

    /**
     * Pushes [token] to the relay if the tunnel is connected.
     *
     * Silently no-ops (at debug level) when the tunnel is not in a stable connected
     * state — the next wake will pull the current token.
     */
    suspend fun registerToken(token: String) {
        val currentState = tunnelClient.state.value
        if (currentState != TunnelState.CONNECTED && currentState != TunnelState.ACTIVE) {
            Log.d(TAG, "Tunnel state=$currentState; FCM token refresh deferred to next wake")
            return
        }
        try {
            tunnelClient.sendFcmToken(token)
            Log.i(TAG, "Forwarded refreshed FCM token to relay")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to push refreshed FCM token; next wake will pull", e)
        }
    }

    companion object {
        private const val TAG = "FcmTokenRegistrar"
    }
}
