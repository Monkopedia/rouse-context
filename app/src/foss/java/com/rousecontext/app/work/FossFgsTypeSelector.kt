package com.rousecontext.app.work

import android.content.pm.ServiceInfo
import android.os.Build
import com.rousecontext.work.FgsTypeSelector

/**
 * FOSS [FgsTypeSelector]: returns `specialUse` when the user has opted into
 * "Ignore daily time limit", otherwise `dataSync`.
 *
 * `specialUse` is only a valid foreground-service type on API 34+; the Android
 * 15 (API 35) 6h/24h `dataSync` cap is what this opt-in escapes. On older
 * devices we always fall back to `dataSync` (there is no cap to escape, and the
 * type would not be recognised). This seam ONLY changes the FGS type — the
 * adaptive idle-timeout teardown still governs when the tunnel drops.
 *
 * [ignoreDailyTimeLimit] is read synchronously at `startForeground()` time, so
 * it is backed by [IgnoreDailyTimeLimitState]'s in-memory snapshot rather than a
 * suspending DataStore read.
 */
class FossFgsTypeSelector(private val ignoreDailyTimeLimit: () -> Boolean) : FgsTypeSelector {

    override fun foregroundServiceType(): Int =
        if (supportsSpecialUse() && ignoreDailyTimeLimit()) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }

    private fun supportsSpecialUse(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
}
