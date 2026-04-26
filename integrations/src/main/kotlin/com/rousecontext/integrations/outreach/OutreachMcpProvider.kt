package com.rousecontext.integrations.outreach

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.rousecontext.api.LaunchRequestNotifierApi
import com.rousecontext.mcp.core.Clock
import com.rousecontext.mcp.core.McpClientContext
import com.rousecontext.mcp.core.McpServerProvider
import com.rousecontext.mcp.core.RateLimiter
import com.rousecontext.mcp.core.SystemClock
import com.rousecontext.mcp.tool.McpTool
import com.rousecontext.mcp.tool.ToolResult
import com.rousecontext.mcp.tool.registerTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * MCP server provider for outreach actions: launching apps, opening links,
 * copying to clipboard, sending notifications, and optionally controlling DND.
 *
 * Tools are authored with the [McpTool] DSL; this provider just wires
 * dependencies and registers them.
 */
class OutreachMcpProvider(
    private val context: Context,
    private val dndEnabled: Boolean = false,
    clock: Clock = SystemClock,
    private val canLaunchDirectly: () -> Boolean = { defaultCanLaunchDirectly(context) },
    private val launchNotifier: LaunchRequestNotifierApi? = null
) : McpServerProvider {

    override val id = "outreach"
    override val displayName = "Outreach"

    private val notificationIdCounter = AtomicInteger(NOTIFICATION_ID_BASE)
    private val notificationRateLimiter = RateLimiter(
        maxRequests = MAX_NOTIFICATIONS_PER_MINUTE,
        windowMs = RATE_LIMIT_WINDOW_MS,
        clock = clock
    )

    override fun register(server: Server) {
        ensureNotificationChannel()
        server.registerTool { LaunchAppTool(context, canLaunchDirectly, launchNotifier) }
        server.registerTool { OpenLinkTool(context, canLaunchDirectly, launchNotifier) }
        server.registerTool { CopyToClipboardTool(context) }
        server.registerTool {
            SendNotificationTool(
                context = context,
                rateLimiter = notificationRateLimiter,
                idCounter = notificationIdCounter
            )
        }
        server.registerTool { ListInstalledAppsTool(context) }
        server.registerTool { CreateNotificationChannelTool(context) }
        server.registerTool { ListNotificationChannelsTool(context) }
        server.registerTool { DeleteNotificationChannelTool(context) }
        if (dndEnabled) {
            server.registerTool { GetDndStateTool(context) }
            server.registerTool { SetDndStateTool(context) }
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications sent by AI clients via Rouse Context"
            }
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "outreach"
        const val AI_CHANNEL_PREFIX = "rouse_ai_"
        internal const val CHANNEL_NAME = "AI Outreach"
        internal const val NOTIFICATION_ID_BASE = 10000
        internal const val MAX_NOTIFICATIONS_PER_MINUTE = 10
        internal const val RATE_LIMIT_WINDOW_MS = 60_000L
        internal const val MAX_NOTIFICATION_ACTIONS = 3
        internal const val TAG = "OutreachMcp"

        /**
         * Default "can launch activity directly" check used when the caller does
         * not supply an explicit predicate. Pre-Android-14 background launches via
         * PendingIntent are allowed; on Android 14+ the sender must hold BAL, which
         * SYSTEM_ALERT_WINDOW provides (`Settings.canDrawOverlays`).
         */
        fun defaultCanLaunchDirectly(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                return true
            }
            return Settings.canDrawOverlays(context)
        }
    }
}

// ---------- tools ----------

internal class LaunchAppTool(
    private val context: Context,
    private val canLaunchDirectly: () -> Boolean,
    private val launchNotifier: LaunchRequestNotifierApi?
) : McpTool() {
    override val name = "launch_app"
    override val description = "Launch an installed app by package name."

    val packageName by stringParam("package_name", "Reverse-DNS, e.g. com.example.app")
    val extras by mapParam("extras", "").optional()

    override suspend fun execute(): ToolResult {
        val pkg = packageName!!
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return outreachError("App not found: $pkg")

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        extras?.forEach { (k, v) -> intent.putExtra(k, v) }

        val clientName = coroutineContext[McpClientContext]?.clientName

        return launchActivitySafely(
            context = context,
            canLaunchDirectly = canLaunchDirectly,
            launchNotifier = launchNotifier,
            intent = intent,
            description = "launch $pkg",
            fallback = { launchNotifier?.postLaunchApp(intent, pkg, clientName) }
        )
    }
}

internal class OpenLinkTool(
    private val context: Context,
    private val canLaunchDirectly: () -> Boolean,
    private val launchNotifier: LaunchRequestNotifierApi?
) : McpTool() {
    override val name = "open_link"
    override val description = "Open an http/https URL in the default app."

    val url by stringParam("url", "")

    override suspend fun execute(): ToolResult {
        val u = url!!
        val uri = Uri.parse(u)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return outreachError("Only http and https URLs are allowed, got: $scheme")
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val clientName = coroutineContext[McpClientContext]?.clientName
        return launchActivitySafely(
            context = context,
            canLaunchDirectly = canLaunchDirectly,
            launchNotifier = launchNotifier,
            intent = intent,
            description = "open $u",
            fallback = { launchNotifier?.postOpenLink(intent, u, clientName) }
        )
    }
}

internal class CopyToClipboardTool(private val context: Context) : McpTool() {
    override val name = "copy_to_clipboard"
    override val description = "Copy text to the clipboard."

    val text by stringParam("text", "")
    val label by stringParam("label", "").optional()

    override suspend fun execute(): ToolResult {
        withContext(Dispatchers.Main) {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText(label ?: "Copied text", text))
        }
        return outreachSuccess("Copied to clipboard")
    }
}

internal class SendNotificationTool(
    private val context: Context,
    private val rateLimiter: RateLimiter,
    private val idCounter: AtomicInteger
) : McpTool() {
    override val name = "send_notification"
    override val description = "Post a notification."

    val title by stringParam("title", "")
    val message by stringParam("message", "")
    val priority by stringParam("priority", "")
        .optional().choices("low", "default", "high")
    val channelId by stringParam("channel_id", "From create_notification_channel").optional()

    override suspend fun execute(): ToolResult {
        if (!rateLimiter.tryAcquire("send_notification")) {
            return outreachError(
                "Rate limited: max ${OutreachMcpProvider.MAX_NOTIFICATIONS_PER_MINUTE} per minute"
            )
        }
        val resolved = resolveChannelId(channelId)
        val notificationId = idCounter.getAndIncrement()
        val builder = NotificationCompat.Builder(context, resolved)
            .setSmallIcon(com.rousecontext.api.R.drawable.ic_stat_rouse)
            .setContentTitle(title!!)
            .setContentText(message!!)
            .setAutoCancel(true)
            .setPriority(parsePriority(priority))
        addNotificationActions(context, builder, rawArgs, notificationId)
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(notificationId, builder.build())
        return ToolResult.Success(
            OutreachJson.encodeToString(SendNotificationResult(notificationId = notificationId))
        )
    }
}

internal class ListInstalledAppsTool(private val context: Context) : McpTool() {
    override val name = "list_installed_apps"
    override val description = "List installed apps."

    val filter by stringParam("filter", "Name substring").optional()

    override suspend fun execute(): ToolResult {
        val apps = queryInstalledApps(context, filter?.lowercase())
        return ToolResult.Success(
            OutreachJson.encodeToString(ListSerializer(InstalledApp.serializer()), apps)
        )
    }
}

internal class CreateNotificationChannelTool(private val context: Context) : McpTool() {
    override val name = "create_notification_channel"
    override val description = "Create a notification channel."

    val id by stringParam("id", "")
    val channelName by stringParam("name", "User-visible name")
    val channelDescription by stringParam("description", "").optional()
    val importance by stringParam("importance", "")
        .default("default").choices("min", "low", "default", "high")
    val vibrate by boolParam("vibrate", "").optional()
    val soundEnabled by boolParam("sound", "Play sound").default(true)
    val showBadge by boolParam("show_badge", "").optional()

    override suspend fun execute(): ToolResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return outreachSuccess(
                "Channel creation not supported on this Android version (API < 26). " +
                    "Notifications will still work with default settings."
            )
        }
        val rawId = id!!
        val channelId = if (rawId.startsWith(OutreachMcpProvider.AI_CHANNEL_PREFIX)) {
            rawId
        } else {
            "${OutreachMcpProvider.AI_CHANNEL_PREFIX}$rawId"
        }
        val level = parseImportance(importance)
        val wantsSound = soundEnabled
        val wantsVibrate = vibrate
        val wantsBadge = showBadge
        val desc = channelDescription
        val channel = NotificationChannel(channelId, channelName, level).apply {
            desc?.let { description = it }
            wantsVibrate?.let { enableVibration(it) }
            if (wantsSound == false) setSound(null, null)
            wantsBadge?.let { setShowBadge(it) }
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
        return ToolResult.Success(
            OutreachJson.encodeToString(buildChannelDto(nm.getNotificationChannel(channelId)))
        )
    }
}

internal class ListNotificationChannelsTool(private val context: Context) : McpTool() {
    override val name = "list_notification_channels"
    override val description = "List AI-created notification channels."

    override suspend fun execute(): ToolResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return ToolResult.Success("[]")
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        val channels = nm.notificationChannels
            .filter { it.id.startsWith(OutreachMcpProvider.AI_CHANNEL_PREFIX) }
            .map { buildChannelDto(it) }
        return ToolResult.Success(
            OutreachJson.encodeToString(
                ListSerializer(NotificationChannelDto.serializer()),
                channels
            )
        )
    }
}

internal class DeleteNotificationChannelTool(private val context: Context) : McpTool() {
    override val name = "delete_notification_channel"
    override val description = "Delete an AI-created notification channel."

    val id by stringParam("id", "")

    override suspend fun execute(): ToolResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return outreachSuccess(
                "Channel deletion not supported on this Android version (API < 26)"
            )
        }
        val rawId = id!!
        val channelId = if (rawId.startsWith(OutreachMcpProvider.AI_CHANNEL_PREFIX)) {
            rawId
        } else {
            "${OutreachMcpProvider.AI_CHANNEL_PREFIX}$rawId"
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        val existing = nm.getNotificationChannel(channelId)
            ?: return outreachError("Channel not found: $channelId")
        nm.deleteNotificationChannel(channelId)
        return outreachSuccess("Deleted channel: ${existing.name}")
    }
}

internal class GetDndStateTool(private val context: Context) : McpTool() {
    override val name = "get_dnd_state"
    override val description = "Get Do Not Disturb state."

    override suspend fun execute(): ToolResult {
        val nm = context.getSystemService(NotificationManager::class.java)
        val filter = nm.currentInterruptionFilter
        val enabled = filter != NotificationManager.INTERRUPTION_FILTER_ALL
        val mode = filterToMode(filter)
        return ToolResult.Success(
            OutreachJson.encodeToString(DndState(enabled = enabled, mode = mode))
        )
    }
}

internal class SetDndStateTool(private val context: Context) : McpTool() {
    override val name = "set_dnd_state"
    override val description = "Set Do Not Disturb state."

    val enabled by boolParam("enabled", "")
    val mode by stringParam("mode", "")
        .optional().choices("total_silence", "priority_only", "alarms_only")

    override suspend fun execute(): ToolResult {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            return outreachError("ACCESS_NOTIFICATION_POLICY permission not granted")
        }
        val previousFilter = nm.currentInterruptionFilter
        val previousEnabled =
            previousFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        val previousMode = filterToMode(previousFilter)
        val newFilter = if (enabled != true) {
            NotificationManager.INTERRUPTION_FILTER_ALL
        } else {
            modeToFilter(mode)
        }
        nm.setInterruptionFilter(newFilter)
        return ToolResult.Success(
            OutreachJson.encodeToString(
                SetDndStateResult(
                    previousState = DndState(
                        enabled = previousEnabled,
                        mode = previousMode
                    )
                )
            )
        )
    }
}

// ---------- shared helpers ----------

/**
 * Launch an activity, or fall back to a tap-to-launch notification when the
 * app cannot start activities directly from the background.
 */
@Suppress("TooGenericExceptionCaught", "LongParameterList")
internal fun launchActivitySafely(
    context: Context,
    canLaunchDirectly: () -> Boolean,
    launchNotifier: LaunchRequestNotifierApi?,
    intent: Intent,
    description: String,
    fallback: () -> Int?
): ToolResult {
    if (!canLaunchDirectly() && launchNotifier != null) {
        return try {
            val id = fallback()
            if (id != null) {
                outreachSuccess("Notification posted: tap to $description")
            } else {
                outreachError("Failed to $description: no fallback notifier available")
            }
        } catch (e: Exception) {
            Log.e(OutreachMcpProvider.TAG, "Failed to post launch notification for $description", e)
            outreachError(
                "Failed to $description: ${e.javaClass.simpleName} - ${e.message}"
            )
        }
    }
    return try {
        val pendingIntent = PendingIntent.getActivity(
            context,
            intent.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        pendingIntent.send()
        outreachSuccess("Launched: $description")
    } catch (e: android.content.ActivityNotFoundException) {
        Log.e(OutreachMcpProvider.TAG, "No activity found to $description", e)
        outreachError("No app found to $description: ${e.message}")
    } catch (e: SecurityException) {
        Log.e(OutreachMcpProvider.TAG, "Permission denied to $description", e)
        outreachError("Permission denied to $description: ${e.message}")
    } catch (e: PendingIntent.CanceledException) {
        Log.e(OutreachMcpProvider.TAG, "PendingIntent cancelled for $description", e)
        outreachError("Failed to $description: activity launch was cancelled")
    } catch (e: Exception) {
        Log.e(OutreachMcpProvider.TAG, "Unexpected error trying to $description", e)
        outreachError("Failed to $description: ${e.javaClass.simpleName} - ${e.message}")
    }
}

internal fun resolveChannelId(channelId: String?): String {
    if (channelId == null) return OutreachMcpProvider.CHANNEL_ID
    return if (channelId.startsWith(OutreachMcpProvider.AI_CHANNEL_PREFIX)) {
        channelId
    } else {
        "${OutreachMcpProvider.AI_CHANNEL_PREFIX}$channelId"
    }
}

internal fun addNotificationActions(
    context: Context,
    builder: NotificationCompat.Builder,
    args: kotlinx.serialization.json.JsonObject?,
    notificationId: Int
) {
    args?.get("actions")?.jsonArray
        ?.take(OutreachMcpProvider.MAX_NOTIFICATION_ACTIONS)
        ?.forEach { actionElement ->
            val action = actionElement.jsonObject
            val label = action["label"]?.jsonPrimitive?.content ?: return@forEach
            val url = action["url"]?.jsonPrimitive?.content ?: return@forEach
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase()
            if (scheme == "http" || scheme == "https") {
                val intent = Intent(Intent.ACTION_VIEW, uri)
                val pi = PendingIntent.getActivity(
                    context,
                    notificationId + label.hashCode(),
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
                )
                builder.addAction(0, label, pi)
            }
        }
}

@Suppress("MagicNumber")
internal fun buildChannelDto(channel: NotificationChannel): NotificationChannelDto {
    val imp = when (channel.importance) {
        NotificationManager.IMPORTANCE_MIN -> "min"
        NotificationManager.IMPORTANCE_LOW -> "low"
        NotificationManager.IMPORTANCE_HIGH -> "high"
        else -> "default"
    }
    return NotificationChannelDto(
        id = channel.id,
        name = channel.name.toString(),
        description = channel.description ?: "",
        importance = imp,
        vibration = channel.shouldVibrate(),
        sound = channel.sound != null,
        showBadge = channel.canShowBadge()
    )
}

@Suppress("DEPRECATION")
internal fun queryInstalledApps(context: Context, filter: String?): List<InstalledApp> {
    val pm = context.packageManager
    val allApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
    } else {
        pm.getInstalledApplications(0)
    }
    return allApps.mapNotNull { appInfo ->
        val isSystem = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
        val isUpdatedSystem = appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        val hasLauncher = pm.getLaunchIntentForPackage(appInfo.packageName) != null
        if (isSystem && !isUpdatedSystem && !hasLauncher) return@mapNotNull null
        val appName = pm.getApplicationLabel(appInfo).toString()
        if (filter != null && !appName.lowercase().contains(filter)) {
            null
        } else {
            InstalledApp(
                `package` = appInfo.packageName,
                name = appName,
                system = isSystem && !isUpdatedSystem
            )
        }
    }.sortedBy { it.`package` }
}

internal fun parseImportance(importance: String?): Int = when (importance?.lowercase()) {
    "min" -> NotificationManager.IMPORTANCE_MIN
    "low" -> NotificationManager.IMPORTANCE_LOW
    "high" -> NotificationManager.IMPORTANCE_HIGH
    else -> NotificationManager.IMPORTANCE_DEFAULT
}

internal fun parsePriority(priority: String?): Int = when (priority?.lowercase()) {
    "low" -> NotificationCompat.PRIORITY_LOW
    "high" -> NotificationCompat.PRIORITY_HIGH
    else -> NotificationCompat.PRIORITY_DEFAULT
}

internal fun filterToMode(filter: Int): String = when (filter) {
    NotificationManager.INTERRUPTION_FILTER_NONE -> "total_silence"
    NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority_only"
    NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms_only"
    else -> "off"
}

internal fun modeToFilter(mode: String?): Int = when (mode) {
    "total_silence" -> NotificationManager.INTERRUPTION_FILTER_NONE
    "alarms_only" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
    else -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
}

internal fun outreachError(message: String): ToolResult = ToolResult.Error(
    OutreachJson.encodeToString(OutreachError(error = message))
)

internal fun outreachSuccess(message: String): ToolResult = ToolResult.Success(
    OutreachJson.encodeToString(OutreachOk(message = message))
)
