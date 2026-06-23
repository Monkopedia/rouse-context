package com.rousecontext.app.work

import android.content.pm.ServiceInfo
import com.rousecontext.work.FgsTypeSelector

/**
 * Google-build [FgsTypeSelector]: the tunnel foreground service always runs as
 * `dataSync`. The Play build never declares `specialUse` — declaring it triggers
 * Play review, and Play steers persistent-connection use cases to FCM (which
 * this build already uses for wakeups). The Android 15 6h/24h `dataSync` cap is
 * accepted here; the adaptive idle timeout keeps usage well under it.
 */
class GoogleFgsTypeSelector : FgsTypeSelector {

    override fun foregroundServiceType(): Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
}
