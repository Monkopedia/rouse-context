package com.rousecontext.work

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.rousecontext.tunnel.CertificateStore
import kotlinx.coroutines.tasks.await

/**
 * Registers the device's FCM token in Firestore so the relay can send wake messages.
 *
 * The token is written to `devices/{subdomain}` with field `fcm_token`.
 */
class FcmTokenRegistrar(
    private val certificateStore: CertificateStore,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {

    /**
     * Writes [token] to the device's Firestore document.
     * If no subdomain is configured yet, the write is skipped.
     */
    suspend fun registerToken(token: String) {
        val subdomain = certificateStore.getSubdomain()
        if (subdomain == null) {
            Log.w(TAG, "No subdomain configured, skipping FCM token registration")
            return
        }

        firestore.collection(COLLECTION)
            .document(subdomain)
            .update(FIELD, token)
            .await()

        Log.i(TAG, "FCM token registered for $subdomain")
    }

    /**
     * Fetches the current FCM token and registers it.
     * Called on app startup to ensure the token is always up to date.
     */
    suspend fun registerCurrentToken() {
        val token = FirebaseMessaging.getInstance().token.await()
        registerToken(token)
    }

    companion object {
        private const val TAG = "FcmTokenRegistrar"
        private const val COLLECTION = "devices"
        private const val FIELD = "fcm_token"
    }
}
