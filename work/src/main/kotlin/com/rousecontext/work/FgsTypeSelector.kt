package com.rousecontext.work

/**
 * Supplies the foreground-service type [TunnelForegroundService] passes to
 * `startForeground()`.
 *
 * The binding is flavor-specific (see each distribution's `DistributionModule`):
 *
 * - **google** always returns `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC`.
 *   The Play build never declares `specialUse` (declaring it triggers Play
 *   review, which steers persistent-connection use cases to FCM).
 * - **foss** returns `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` when the user has
 *   opted into "Ignore daily time limit" (and the device supports it), else
 *   `FOREGROUND_SERVICE_TYPE_DATA_SYNC`. `specialUse` has no Android 15
 *   6h/24h cap, so a legitimately long, active day is not guillotined.
 *
 * This seam ONLY changes the FGS type. It does not disable idle timeouts — the
 * adaptive idle-timeout teardown still governs when the tunnel drops.
 */
fun interface FgsTypeSelector {
    fun foregroundServiceType(): Int
}
