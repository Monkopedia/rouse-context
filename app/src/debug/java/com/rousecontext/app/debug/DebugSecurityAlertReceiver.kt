package com.rousecontext.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rousecontext.notifications.AndroidSecurityCheckNotifier
import com.rousecontext.notifications.NotificationChannels
import com.rousecontext.notifications.SecurityCheckNotifier

/**
 * Debug-only broadcast receiver that posts a real "Security Alert" notification
 * on demand. Exists solely so maintainers can screenshot the production
 * notification (self-cert or CT-log failure) for embedding in user-facing docs
 * (`docs/user/security.md`).
 *
 * Triggering the underlying security check to genuinely fail would require
 * standing up a fake relay with a bad cert or a CT-log responder with a
 * surprise issuer. This receiver instead calls the exact same
 * [AndroidSecurityCheckNotifier.postAlert] function production uses, so the
 * resulting notification is pixel-identical.
 *
 * Only compiled into the `debug` build variant — never shipped in release.
 *
 * Trigger from a host shell:
 * ```
 * # self-cert alert:
 * adb shell am broadcast \
 *   -a com.rousecontext.debug.FIRE_SECURITY_ALERT \
 *   --es check self_cert \
 *   -n com.rousecontext.debug/com.rousecontext.app.debug.DebugSecurityAlertReceiver
 *
 * # CT-log alert:
 * adb shell am broadcast \
 *   -a com.rousecontext.debug.FIRE_SECURITY_ALERT \
 *   --es check ct_log \
 *   -n com.rousecontext.debug/com.rousecontext.app.debug.DebugSecurityAlertReceiver
 * ```
 */
class DebugSecurityAlertReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE_SECURITY_ALERT) {
            Log.w(TAG, "Unknown action: ${intent.action}")
            return
        }
        val checkExtra = intent.getStringExtra(EXTRA_CHECK) ?: DEFAULT_CHECK
        val (check, reason) = when (checkExtra) {
            "self_cert" ->
                SecurityCheckNotifier.SecurityCheck.SELF_CERT to
                    "Leaf certificate fingerprint does not match any known fingerprint"
            "ct_log" ->
                SecurityCheckNotifier.SecurityCheck.CT_LOG to
                    "Unexpected certificate issuer(s) for this device"
            else -> {
                Log.w(TAG, "Unknown check extra '$checkExtra'; defaulting to self_cert")
                SecurityCheckNotifier.SecurityCheck.SELF_CERT to
                    "Leaf certificate fingerprint does not match any known fingerprint"
            }
        }
        NotificationChannels.createAll(context)
        AndroidSecurityCheckNotifier(context).postAlert(check, reason)
        Log.i(TAG, "Posted Security Alert ($checkExtra) via debug trigger")
    }

    companion object {
        private const val TAG = "DebugSecurityAlertReceiver"
        private const val ACTION_FIRE_SECURITY_ALERT = "com.rousecontext.debug.FIRE_SECURITY_ALERT"
        private const val EXTRA_CHECK = "check"
        private const val DEFAULT_CHECK = "self_cert"
    }
}
