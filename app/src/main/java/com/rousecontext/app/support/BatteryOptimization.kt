package com.rousecontext.app.support

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings

/**
 * Battery-optimization status and the user-facing flow to change it.
 *
 * Reliable FCM wake-ups depend on the app being exempt from Doze battery
 * optimization. We surface the current status in Settings (a warning + the
 * idle-timeout "Disable" switch both gate on it) and offer a "Fix this" button
 * that sends the user to the system screen where they can grant the exemption.
 *
 * We deliberately use [Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS]
 * (the optimization list the user picks the app from) rather than
 * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (the direct allow/deny dialog):
 * the direct request needs the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
 * permission, which Google Play restricts to apps whose core function requires
 * it. The settings-list route needs no special permission.
 */
object BatteryOptimization {

    /** True if this app is currently exempt from Doze battery optimization. */
    fun isExempt(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Intent that opens the system battery-optimization settings list, where the
     * user can mark this app "Don't optimize". Launch from an Activity context.
     */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}
