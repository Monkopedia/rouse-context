package com.rousecontext.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.rousecontext.api.LaunchRequestNotifierApi
import com.rousecontext.api.R as ApiR
import java.util.concurrent.atomic.AtomicInteger

/**
 * Posts notifications that act as tap-to-launch fallbacks when the app cannot
 * start an activity directly from the background.
 *
 * On Android 14+ the sender of a PendingIntent must hold background-activity-start
 * privilege at `send()` time; our `FOREGROUND_SERVICE_TYPE_DATA_SYNC` does NOT
 * grant BAL. Notification taps, however, are always allowed — so we surface the
 * AI client's launch request as a notification the user taps to execute.
 *
 * See GitHub issue #102 for context.
 */
class LaunchRequestNotifier(private val context: Context) : LaunchRequestNotifierApi {

    private val counter = AtomicInteger(0)

    /**
     * Post a notification that, when tapped, launches the given app.
     *
     * @param launchIntent The activity [Intent] to fire (typically from
     *   `PackageManager.getLaunchIntentForPackage`).
     * @param packageName Target app package, used to resolve a human-readable name.
     * @return The posted notification id.
     */
    override fun postLaunchApp(launchIntent: Intent, packageName: String): Int {
        val appName = resolveAppName(packageName)
        return post(
            title = "AI client wants to open $appName",
            body = appName,
            intent = launchIntent
        )
    }

    /**
     * Post a notification that, when tapped, opens the given URL.
     *
     * @param viewIntent The `ACTION_VIEW` intent holding the URL.
     * @param url The URL string (displayed in the notification body, possibly
     *   truncated by the system).
     * @return The posted notification id.
     */
    override fun postOpenLink(viewIntent: Intent, url: String): Int = post(
        title = "AI client wants to open a webpage",
        body = url,
        intent = viewIntent
    )

    private fun post(title: String, body: String, intent: Intent): Int {
        val id = BASE_ID + counter.getAndIncrement()

        // Ensure the intent can actually start a new activity when fired from a
        // notification-tap context.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val pendingIntent = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(
            context,
            NotificationChannels.OUTREACH_LAUNCH_CHANNEL_ID
        )
            .setSmallIcon(ApiR.drawable.ic_stat_rouse)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(0, "Open", pendingIntent)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(id, notification)
        return id
    }

    private fun resolveAppName(packageName: String): String = try {
        val pm = context.packageManager
        val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(0L)
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(packageName, 0)
        }
        pm.getApplicationLabel(appInfo).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }

    companion object {
        /** Notification id offset for outreach launch-request notifications. */
        const val BASE_ID = 7000
    }
}
