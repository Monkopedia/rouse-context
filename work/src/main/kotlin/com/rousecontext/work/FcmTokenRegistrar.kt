package com.rousecontext.work

import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

/**
 * Manages FCM token awareness for the app.
 *
 * The actual delivery of FCM tokens to the relay happens over the tunnel
 * WebSocket connection (see [TunnelForegroundService.sendFcmToken]).
 * The relay stores tokens in Firestore using its service account.
 *
 * This class only fetches and logs the current token; it does NOT write
 * directly to Firestore (the app's Firebase client lacks write permissions).
 */
class FcmTokenRegistrar {

    /**
     * Logs that a new token was received.
     * Actual delivery to the relay happens via the tunnel WebSocket on next connect.
     */
    @Suppress("UnusedParameter")
    fun registerToken(token: String) {
        Log.i(TAG, "FCM token refreshed (will be sent to relay on next tunnel connect)")
    }

    /**
     * Fetches the current FCM token and logs it.
     * Called on app startup; the tunnel service sends the token to the relay on connect.
     */
    suspend fun registerCurrentToken() {
        val token = FirebaseMessaging.getInstance().token.await()
        Log.d(TAG, "Current FCM token available (length=${token.length})")
    }

    companion object {
        private const val TAG = "FcmTokenRegistrar"
    }
}
