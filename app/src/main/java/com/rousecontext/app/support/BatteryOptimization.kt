package com.rousecontext.app.support

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * Battery-optimization status and the user-facing flow to change it.
 *
 * Reliable background wake-ups depend on the app being exempt from Doze battery
 * optimization. On the FOSS build this exemption is *required* — a third-party
 * UnifiedPush distributor cannot hand our app the temporary power-allowlist that
 * Google Play Services grants the FCM build, so without the standing exemption
 * the background start of the tunnel foreground service is blocked and the wake
 * is dropped (issue #483). We surface the status in Settings (#453) and, on the
 * FOSS build, on Home, and offer a "Fix this" action.
 *
 * "Fix this" uses [Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS] — the
 * one-tap allow/deny dialog — with a `package:` URI, instead of
 * [Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS] (the optimization list
 * the user has to hunt through). The dialog needs the
 * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission, which Google Play restricts
 * to apps whose core function requires it. We don't ship to Play (the Google
 * build is GitHub-distributed; the FOSS build goes to F-Droid, which accepts the
 * permission — ntfy uses it), so that restriction doesn't bind and we get the
 * better one-tap flow. This supersedes the 2026-06-02 #453 settings-list choice.
 */
object BatteryOptimization {

    /** True if this app is currently exempt from Doze battery optimization. */
    fun isExempt(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Intent that opens the system one-tap "allow ignoring battery optimization?"
     * dialog for this app, so a single confirmation grants the exemption. Launch
     * from an Activity context.
     */
    fun requestExemptionIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
}

/**
 * Maps a trigger flow (emit anything to re-check) into a flow of "is this app
 * exempt from battery optimization". An initial value is emitted immediately on
 * collection. Mirrors
 * [com.rousecontext.app.state.notificationPermissionFlow]: callers drive
 * [triggers] from lifecycle events (ON_RESUME) so the Home battery-optimization
 * banner reflects the current state after the user returns from the OS dialog.
 */
fun batteryExemptFlow(context: Context, triggers: Flow<Unit>): Flow<Boolean> = triggers
    .onStart { emit(Unit) }
    .map { BatteryOptimization.isExempt(context) }
    .distinctUntilChanged()
