package com.rousecontext.app.push

import com.rousecontext.work.ConnectPushReporter

/**
 * `foss`-flavor [ConnectPushReporter]: a no-op. The foss build wakes via
 * UnifiedPush, whose endpoint is reported to the relay by
 * [com.rousecontext.app.delivery.UnifiedPushBackgroundDelivery] (on endpoint
 * delivery / rotation), not on each tunnel connect. There is no FCM token to
 * push, so the per-connect sync does nothing here — keeping the foss APK free
 * of any `firebase-messaging` linkage (issue #476).
 */
object NoOpConnectPushReporter : ConnectPushReporter {
    override suspend fun reportOnConnect() = Unit
}
