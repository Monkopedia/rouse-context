package com.rousecontext.outreach

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.rousecontext.mcp.core.Clock
import com.rousecontext.mcp.core.McpServerProvider
import com.rousecontext.mcp.core.RateLimiter
import com.rousecontext.mcp.core.SystemClock
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * MCP server provider for outreach actions: launching apps, opening links,
 * copying to clipboard, sending notifications, and optionally controlling DND.
 */
class OutreachMcpProvider(
    private val context: Context,
    private val dndEnabled: Boolean = false,
    clock: Clock = SystemClock
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
        registerLaunchApp(server)
        registerOpenLink(server)
        registerCopyToClipboard(server)
        registerSendNotification(server)
        registerListInstalledApps(server)
        if (dndEnabled) {
            registerGetDndState(server)
            registerSetDndState(server)
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

    private fun registerLaunchApp(server: Server) {
        server.addTool(
            name = "launch_app",
            description = "Launch an installed app on the device by package name",
            inputSchema = launchAppSchema()
        ) { request ->
            val packageName = request.params.arguments?.get("package_name")?.jsonPrimitive?.content
                ?: return@addTool errorResult("Missing package_name")

            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: return@addTool errorResult("App not found: $packageName")

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            // Add simple string extras only (security: no arbitrary types)
            request.params.arguments?.get("extras")?.jsonObject?.forEach { (key, value) ->
                intent.putExtra(key, value.jsonPrimitive.content)
            }

            launchActivitySafely(intent, "launch $packageName")
        }
    }

    private fun registerOpenLink(server: Server) {
        server.addTool(
            name = "open_link",
            description = "Open a URL in the default browser or appropriate app",
            inputSchema = stringParamSchema("url", "URL to open (http or https only)")
        ) { request ->
            val url = request.params.arguments?.get("url")?.jsonPrimitive?.content
                ?: return@addTool errorResult("Missing url")

            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                return@addTool errorResult(
                    "Only http and https URLs are allowed, got: $scheme"
                )
            }

            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            launchActivitySafely(intent, "open $url")
        }
    }

    private fun registerCopyToClipboard(server: Server) {
        server.addTool(
            name = "copy_to_clipboard",
            description = "Copy text to the device clipboard",
            inputSchema = clipboardSchema()
        ) { request ->
            val text = request.params.arguments?.get("text")?.jsonPrimitive?.content
                ?: return@addTool errorResult("Missing text")
            val label = request.params.arguments?.get("label")?.jsonPrimitive?.content
                ?: "Copied text"

            withContext(Dispatchers.Main) {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
            }

            successResult("Copied to clipboard")
        }
    }

    private fun registerSendNotification(server: Server) {
        server.addTool(
            name = "send_notification",
            description = "Send a notification to the user on the device",
            inputSchema = notificationSchema()
        ) { request ->
            val title = request.params.arguments?.get("title")?.jsonPrimitive?.content
                ?: return@addTool errorResult("Missing title")
            val message = request.params.arguments?.get("message")?.jsonPrimitive?.content
                ?: return@addTool errorResult("Missing message")
            val priority = request.params.arguments?.get("priority")?.jsonPrimitive?.content

            if (!notificationRateLimiter.tryAcquire("send_notification")) {
                return@addTool errorResult(
                    "Rate limited: max $MAX_NOTIFICATIONS_PER_MINUTE per minute"
                )
            }

            val notificationId = notificationIdCounter.getAndIncrement()
            val builder = buildNotification(title, message, priority)
            addNotificationActions(builder, request, notificationId)

            val nm = context.getSystemService(NotificationManager::class.java)
            nm.notify(notificationId, builder.build())
            CallToolResult(
                content = listOf(
                    TextContent(
                        """{"success":true,"notification_id":$notificationId}"""
                    )
                )
            )
        }
    }

    private fun buildNotification(
        title: String,
        message: String,
        priority: String?
    ): NotificationCompat.Builder = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(com.rousecontext.api.R.drawable.ic_stat_rouse)
        .setContentTitle(title)
        .setContentText(message)
        .setAutoCancel(true)
        .setPriority(parsePriority(priority))

    private fun addNotificationActions(
        builder: NotificationCompat.Builder,
        request: io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest,
        notificationId: Int
    ) {
        request.params.arguments?.get("actions")?.jsonArray
            ?.take(MAX_NOTIFICATION_ACTIONS)
            ?.forEach { actionElement ->
                val action = actionElement.jsonObject
                val label = action["label"]?.jsonPrimitive?.content
                    ?: return@forEach
                val url = action["url"]?.jsonPrimitive?.content
                    ?: return@forEach
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

    private fun registerListInstalledApps(server: Server) {
        server.addTool(
            name = "list_installed_apps",
            description = "List installed apps on the device",
            inputSchema = optionalStringSchema("filter", "Optional text to filter apps by name")
        ) { request ->
            val filter = request.params.arguments?.get(
                "filter"
            )?.jsonPrimitive?.content?.lowercase()
            val apps = queryInstalledApps(filter)
            CallToolResult(
                content = listOf(TextContent("[${apps.joinToString(",")}]"))
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun queryInstalledApps(filter: String?): List<String> {
        val pm = context.packageManager
        val allApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(
                android.content.pm.PackageManager.ApplicationInfoFlags.of(0)
            )
        } else {
            pm.getInstalledApplications(0)
        }
        return allApps.mapNotNull { appInfo ->
            // Filter out system apps that aren't user-visible (no launcher intent, not updated)
            val isSystem = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
            val isUpdatedSystem = appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
            val hasLauncher = pm.getLaunchIntentForPackage(appInfo.packageName) != null
            if (isSystem && !isUpdatedSystem && !hasLauncher) return@mapNotNull null

            val appName = pm.getApplicationLabel(appInfo).toString()
            if (filter != null && !appName.lowercase().contains(filter)) {
                null
            } else {
                val systemFlag = isSystem && !isUpdatedSystem
                """{"package":"${appInfo.packageName}",""" +
                    """"name":"${appName.escapeJson()}",""" +
                    """"system":$systemFlag}"""
            }
        }.sorted()
    }

    private fun registerGetDndState(server: Server) {
        server.addTool(
            name = "get_dnd_state",
            description = "Check Do Not Disturb status",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList())
        ) { _ ->
            val nm = context.getSystemService(NotificationManager::class.java)
            val filter = nm.currentInterruptionFilter
            val enabled = filter != NotificationManager.INTERRUPTION_FILTER_ALL
            val mode = filterToMode(filter)
            CallToolResult(
                content = listOf(
                    TextContent("""{"enabled":$enabled,"mode":"$mode"}""")
                )
            )
        }
    }

    private fun registerSetDndState(server: Server) {
        server.addTool(
            name = "set_dnd_state",
            description = "Control Do Not Disturb state",
            inputSchema = dndSchema()
        ) { request ->
            handleSetDnd(request)
        }
    }

    private fun handleSetDnd(
        request: io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
    ): CallToolResult {
        val enabledStr = request.params.arguments?.get("enabled")?.jsonPrimitive?.content
        val enabled = enabledStr?.toBooleanStrictOrNull()
            ?: return errorResult(
                "Missing or invalid 'enabled' (must be true/false)"
            )
        val mode = request.params.arguments?.get("mode")?.jsonPrimitive?.content

        val nm = context.getSystemService(NotificationManager::class.java)

        if (!nm.isNotificationPolicyAccessGranted) {
            return errorResult(
                "ACCESS_NOTIFICATION_POLICY permission not granted"
            )
        }

        val previousFilter = nm.currentInterruptionFilter
        val previousEnabled =
            previousFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        val previousMode = filterToMode(previousFilter)

        val newFilter = if (!enabled) {
            NotificationManager.INTERRUPTION_FILTER_ALL
        } else {
            modeToFilter(mode)
        }

        nm.setInterruptionFilter(newFilter)

        return CallToolResult(
            content = listOf(
                TextContent(
                    """{"success":true,"previous_state":""" +
                        """{"enabled":$previousEnabled,"mode":"$previousMode"}}"""
                )
            )
        )
    }

    /**
     * Launch an activity using PendingIntent to avoid Android 10+ background activity
     * start restrictions. Direct startActivity from a Service/Application context is
     * silently dropped on API 29+; PendingIntent.send() works reliably from background.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun launchActivitySafely(intent: Intent, description: String): CallToolResult {
        return try {
            val pendingIntent = PendingIntent.getActivity(
                context,
                intent.hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            pendingIntent.send()
            successResult("Launched: $description")
        } catch (e: android.content.ActivityNotFoundException) {
            Log.e(TAG, "No activity found to $description", e)
            errorResult("No app found to $description: ${e.message}")
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied to $description", e)
            errorResult("Permission denied to $description: ${e.message}")
        } catch (e: PendingIntent.CanceledException) {
            Log.e(TAG, "PendingIntent cancelled for $description", e)
            errorResult("Failed to $description: activity launch was cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error trying to $description", e)
            errorResult("Failed to $description: ${e.javaClass.simpleName} - ${e.message}")
        }
    }

    companion object {
        const val CHANNEL_ID = "outreach"
        private const val CHANNEL_NAME = "AI Outreach"
        private const val NOTIFICATION_ID_BASE = 10000
        private const val MAX_NOTIFICATIONS_PER_MINUTE = 10
        private const val RATE_LIMIT_WINDOW_MS = 60_000L
        private const val MAX_NOTIFICATION_ACTIONS = 3
        private const val TAG = "OutreachMcp"
    }
}

private fun parsePriority(priority: String?): Int = when (priority?.lowercase()) {
    "low" -> NotificationCompat.PRIORITY_LOW
    "high" -> NotificationCompat.PRIORITY_HIGH
    else -> NotificationCompat.PRIORITY_DEFAULT
}

private fun filterToMode(filter: Int): String = when (filter) {
    NotificationManager.INTERRUPTION_FILTER_NONE -> "total_silence"
    NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority_only"
    NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms_only"
    else -> "off"
}

private fun modeToFilter(mode: String?): Int = when (mode) {
    "total_silence" -> NotificationManager.INTERRUPTION_FILTER_NONE
    "alarms_only" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
    else -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
}

private fun errorResult(message: String): CallToolResult = CallToolResult(
    content = listOf(TextContent("""{"success":false,"error":"$message"}""")),
    isError = true
)

private fun successResult(message: String): CallToolResult = CallToolResult(
    content = listOf(TextContent("""{"success":true,"message":"$message"}"""))
)

private fun String.escapeJson(): String = replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")

// --- Input schema builders ---

private fun launchAppSchema() = ToolSchema(
    properties = buildJsonObject {
        put(
            "package_name",
            buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Package name of the app to launch"))
            }
        )
        put(
            "extras",
            buildJsonObject {
                put("type", JsonPrimitive("object"))
                put(
                    "description",
                    JsonPrimitive("Optional string key-value extras to pass to the intent")
                )
                put(
                    "additionalProperties",
                    buildJsonObject { put("type", JsonPrimitive("string")) }
                )
            }
        )
    },
    required = listOf("package_name")
)

private fun stringParamSchema(name: String, desc: String) = ToolSchema(
    properties = buildJsonObject {
        put(
            name,
            buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive(desc))
            }
        )
    },
    required = listOf(name)
)

private fun optionalStringSchema(name: String, desc: String) = ToolSchema(
    properties = buildJsonObject {
        put(
            name,
            buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive(desc))
            }
        )
    },
    required = emptyList()
)

private fun clipboardSchema() = ToolSchema(
    properties = buildJsonObject {
        put(
            "text",
            buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Text to copy"))
            }
        )
        put(
            "label",
            buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Optional label describing the content"))
            }
        )
    },
    required = listOf("text")
)

private fun notificationSchema() = ToolSchema(
    properties = buildJsonObject {
        put(
            "title",
            buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Notification title"))
            }
        )
        put(
            "message",
            buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Notification body text"))
            }
        )
        put(
            "priority",
            buildJsonObject {
                put("type", JsonPrimitive("string"))
                put(
                    "description",
                    JsonPrimitive("Notification priority: low, default, or high")
                )
                put(
                    "enum",
                    kotlinx.serialization.json.JsonArray(
                        listOf(
                            JsonPrimitive("low"),
                            JsonPrimitive("default"),
                            JsonPrimitive("high")
                        )
                    )
                )
            }
        )
        put(
            "actions",
            buildJsonObject {
                put("type", JsonPrimitive("array"))
                put("description", JsonPrimitive("Optional action buttons"))
                put(
                    "items",
                    buildJsonObject {
                        put("type", JsonPrimitive("object"))
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "label",
                                    buildJsonObject {
                                        put("type", JsonPrimitive("string"))
                                    }
                                )
                                put(
                                    "url",
                                    buildJsonObject {
                                        put("type", JsonPrimitive("string"))
                                    }
                                )
                            }
                        )
                        put(
                            "required",
                            kotlinx.serialization.json.JsonArray(
                                listOf(JsonPrimitive("label"), JsonPrimitive("url"))
                            )
                        )
                    }
                )
            }
        )
    },
    required = listOf("title", "message")
)

private fun dndSchema() = ToolSchema(
    properties = buildJsonObject {
        put(
            "enabled",
            buildJsonObject {
                put("type", JsonPrimitive("boolean"))
                put("description", JsonPrimitive("Whether to enable or disable DND"))
            }
        )
        put(
            "mode",
            buildJsonObject {
                put("type", JsonPrimitive("string"))
                put(
                    "description",
                    JsonPrimitive("DND mode: total_silence, priority_only, or alarms_only")
                )
                put(
                    "enum",
                    kotlinx.serialization.json.JsonArray(
                        listOf(
                            JsonPrimitive("total_silence"),
                            JsonPrimitive("priority_only"),
                            JsonPrimitive("alarms_only")
                        )
                    )
                )
            }
        )
    },
    required = listOf("enabled")
)
