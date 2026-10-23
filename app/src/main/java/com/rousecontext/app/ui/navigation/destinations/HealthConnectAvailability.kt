package com.rousecontext.app.ui.navigation.destinations

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.health.connect.client.HealthConnectClient

/**
 * Whether the Health Connect provider can serve requests right now.
 *
 * On Android 13 and lower Health Connect is a separate Play app
 * ([HEALTH_CONNECT_PACKAGE]); when it is missing or
 * too old, the permission-request and settings intents have no handler and
 * launching them throws [ActivityNotFoundException] (#803).
 */
internal fun isHealthConnectAvailable(context: Context): Boolean =
    HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

/**
 * Runs [launch] if Health Connect is available, otherwise sends the user to
 * install or update it. A missing handler at launch time takes the same
 * install path instead of crashing (#803).
 */
internal fun launchHealthConnectOrInstall(context: Context, launch: () -> Unit) {
    if (!isHealthConnectAvailable(context)) {
        openHealthConnectInstall(context)
        return
    }
    try {
        launch()
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "Health Connect reported available but no activity handled the launch", e)
        openHealthConnectInstall(context)
    }
}

/**
 * Opens the Health Connect Play Store listing, falling back to the web
 * listing when no store app handles `market://`. Uses the documented
 * onboarding deep link so Health Connect resumes onboarding after install.
 */
internal fun openHealthConnectInstall(context: Context) {
    for (uri in listOf(HEALTH_CONNECT_MARKET_URI, HEALTH_CONNECT_WEB_URI)) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        } catch (_: ActivityNotFoundException) {
            // Try the next listing.
        }
    }
    Log.w(TAG, "No app can open the Health Connect store listing")
}

private const val TAG = "HealthConnectInstall"

/** The SDK's default provider package; its own constant is internal. */
private const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"

private const val HEALTH_CONNECT_LISTING_QUERY =
    "id=$HEALTH_CONNECT_PACKAGE&url=healthconnect%3A%2F%2Fonboarding"

internal const val HEALTH_CONNECT_MARKET_URI = "market://details?$HEALTH_CONNECT_LISTING_QUERY"

internal const val HEALTH_CONNECT_WEB_URI =
    "https://play.google.com/store/apps/details?$HEALTH_CONNECT_LISTING_QUERY"
