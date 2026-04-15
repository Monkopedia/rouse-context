package com.rousecontext.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rousecontext.notifications.FgsLimitNotifier
import com.rousecontext.notifications.NotificationChannels

/**
 * Debug-only broadcast receiver that posts the "Foreground service limit reached"
 * notification on demand. Exists solely so maintainers can screenshot the real
 * notification (same code path as production) for embedding in user-facing docs
 * (`docs/user/security.md`, `docs/user/troubleshooting.md`).
 *
 * Reproducing the underlying Android FGS daily-budget exhaustion naturally is
 * impractical; this receiver invokes the same [FgsLimitNotifier.postLimitReachedNotification]
 * function that production uses when `startForeground` fails with
 * `ForegroundServiceStartNotAllowedException`, so the resulting notification is
 * pixel-identical to what users will actually see.
 *
 * Only compiled into the `debug` build variant — never shipped in release.
 *
 * Trigger from a host shell:
 * ```
 * adb shell am broadcast \
 *   -a com.rousecontext.debug.FIRE_FGS_LIMIT \
 *   -n com.rousecontext.debug/com.rousecontext.app.debug.DebugFgsLimitReceiver
 * ```
 */
class DebugFgsLimitReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE_FGS_LIMIT) {
            Log.w(TAG, "Unknown action: ${intent.action}")
            return
        }
        // Ensure the notification channel exists — production normally creates
        // it during app startup, but we also guard here so the receiver works
        // on a freshly installed debug build before anything else runs.
        NotificationChannels.createAll(context)
        FgsLimitNotifier.postLimitReachedNotification(context)
        Log.i(TAG, "Posted FGS limit notification via debug trigger")
    }

    companion object {
        private const val TAG = "DebugFgsLimitReceiver"
        private const val ACTION_FIRE_FGS_LIMIT = "com.rousecontext.debug.FIRE_FGS_LIMIT"
    }
}
