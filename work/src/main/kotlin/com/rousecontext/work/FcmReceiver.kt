package com.rousecontext.work

import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives FCM messages and dispatches them to the appropriate handler.
 *
 * - `type: "wake"` starts the [TunnelForegroundService]
 * - `type: "renew"` enqueues a [CertRenewalWorker] via WorkManager
 * - Unknown types are logged and ignored
 */
class FcmReceiver : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val action = FcmDispatch.resolve(message.data)
        Log.d(TAG, "FCM received: type=${message.data["type"]}, action=$action")

        when (action) {
            is FcmAction.StartService -> startTunnelService()
            is FcmAction.EnqueueRenewal -> enqueueCertRenewal()
            is FcmAction.Ignore -> Log.w(TAG, "Ignoring unknown FCM type: ${action.type}")
        }
    }

    override fun onNewToken(token: String) {
        Log.i(TAG, "New FCM token received")
        // Token registration is handled by :app module
    }

    private fun startTunnelService() {
        val intent = Intent(this, TunnelForegroundService::class.java)
        startForegroundService(intent)
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
    }
}
