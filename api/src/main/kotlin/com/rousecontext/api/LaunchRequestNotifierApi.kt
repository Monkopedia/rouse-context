package com.rousecontext.api

import android.content.Intent

/**
 * Fallback notifier contract for integrations that need to post a tap-to-launch
 * notification when they cannot start an activity directly from the background.
 *
 * On Android 14+ the sender of a PendingIntent must hold background-activity-start
 * privilege at `send()` time. The concrete implementation lives in `:notifications`
 * (`LaunchRequestNotifier`) and is wired into providers by `:app`.
 *
 * See GitHub issue #102.
 */
interface LaunchRequestNotifierApi {
    /**
     * Post a tap-to-launch notification for an app launch request.
     *
     * @param clientName Human-readable name of the AI client making the
     *   request (from OAuth DCR). When null, the notification uses a
     *   generic "AI client" label.
     */
    fun postLaunchApp(launchIntent: Intent, packageName: String, clientName: String? = null): Int

    /**
     * Post a tap-to-open notification for a URL open request.
     *
     * @param clientName Human-readable name of the AI client making the
     *   request (from OAuth DCR). When null, the notification uses a
     *   generic "AI client" label.
     */
    fun postOpenLink(viewIntent: Intent, url: String, clientName: String? = null): Int
}
