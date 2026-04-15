package com.rousecontext.integrations.notifications

import android.service.notification.StatusBarNotification
import com.rousecontext.mcp.core.McpServerProvider
import com.rousecontext.mcp.tool.McpTool
import com.rousecontext.mcp.tool.ToolResult
import com.rousecontext.mcp.tool.registerTool
import com.rousecontext.notifications.FieldEncryptor
import io.modelcontextprotocol.kotlin.sdk.server.Server
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * MCP server provider that exposes device notifications as tools.
 *
 * Tools:
 * - `list_active_notifications` - current notifications on the device
 * - `perform_notification_action` - fire an action on a notification
 * - `dismiss_notification` - dismiss a notification
 * - `search_notification_history` - query Room-backed history
 * - `get_notification_stats` - aggregate notification statistics
 *
 * Tools are authored with the [McpTool] DSL; this provider just wires
 * dependencies and registers them.
 *
 * @param dao Room DAO for notification history queries
 * @param activeNotificationSource callback to retrieve active StatusBarNotifications
 * @param actionPerformer callback to perform a notification action by key + action index
 * @param notificationDismisser callback to dismiss a notification by key
 */
class NotificationMcpProvider(
    private val dao: NotificationDao,
    private val activeNotificationSource: () -> Array<StatusBarNotification>,
    private val actionPerformer: (key: String, actionIndex: Int) -> Boolean,
    private val notificationDismisser: (key: String) -> Boolean,
    private val fieldEncryptor: FieldEncryptor? = null,
    private val allowActions: Boolean = true
) : McpServerProvider {

    override val id = "notifications"
    override val displayName = "Notifications"

    override fun register(server: Server) {
        server.registerTool { ListActiveNotificationsTool(activeNotificationSource) }
        server.registerTool {
            PerformNotificationActionTool(
                activeNotificationSource = activeNotificationSource,
                actionPerformer = actionPerformer,
                allowActions = allowActions
            )
        }
        server.registerTool {
            DismissNotificationTool(
                activeNotificationSource = activeNotificationSource,
                notificationDismisser = notificationDismisser,
                allowActions = allowActions
            )
        }
        server.registerTool { SearchNotificationHistoryTool(dao, fieldEncryptor) }
        server.registerTool { GetNotificationStatsTool(dao) }
    }

    companion object {
        internal const val DEFAULT_SEARCH_LIMIT = 50
        internal const val ERR_ACTIONS_DISABLED =
            """{"success":false,"message":"Notification actions are disabled by the user."}"""
    }
}

// ---------- tools ----------

internal class ListActiveNotificationsTool(
    private val activeNotificationSource: () -> Array<StatusBarNotification>
) : McpTool() {
    override val name = "list_active_notifications"
    override val description = "Returns currently posted notifications on the device."

    val filter by stringParam("filter", "Optional app package name pattern to filter by")
        .optional()

    override suspend fun execute(): ToolResult {
        val f = filter
        val notifications = activeNotificationSource()
            .filter { sbn -> f == null || sbn.packageName.contains(f, ignoreCase = true) }

        val result = buildJsonArray {
            for (sbn in notifications) {
                add(sbnToJson(sbn))
            }
        }
        return ToolResult.Success(result.toString())
    }
}

internal class PerformNotificationActionTool(
    private val activeNotificationSource: () -> Array<StatusBarNotification>,
    private val actionPerformer: (key: String, actionIndex: Int) -> Boolean,
    private val allowActions: Boolean
) : McpTool() {
    override val name = "perform_notification_action"
    override val description = "Executes an action button on an active notification." +
        " Rouse Context's own notifications cannot be acted on."

    val notificationKey by stringParam(
        "notification_key",
        "The notification key from list_active_notifications"
    )
    val actionIndex by intParam("action_index", "Zero-based index of the action to perform")

    override suspend fun execute(): ToolResult {
        if (!allowActions) {
            return ToolResult.Error(NotificationMcpProvider.ERR_ACTIONS_DISABLED)
        }

        val key = notificationKey!!

        // Block actions on our own notifications
        val isOwn = activeNotificationSource()
            .any { it.key == key && NotificationCaptureService.isOwnPackage(it.packageName) }
        if (isOwn) {
            return ToolResult.Error(
                """{"success":false,"message":"Cannot act on Rouse Context notifications"}"""
            )
        }

        val success = actionPerformer(key, actionIndex!!)
        val message = if (success) "Action performed" else "Failed to perform action"
        return ToolResult.Success("""{"success":$success,"message":"$message"}""")
    }
}

internal class DismissNotificationTool(
    private val activeNotificationSource: () -> Array<StatusBarNotification>,
    private val notificationDismisser: (key: String) -> Boolean,
    private val allowActions: Boolean
) : McpTool() {
    override val name = "dismiss_notification"
    override val description = "Dismisses an active notification by its key." +
        " Rouse Context's own notifications cannot be dismissed."

    val notificationKey by stringParam("notification_key", "The notification key to dismiss")

    override suspend fun execute(): ToolResult {
        if (!allowActions) {
            return ToolResult.Error(NotificationMcpProvider.ERR_ACTIONS_DISABLED)
        }

        val key = notificationKey!!

        // Block dismissing our own notifications
        val isOwn = activeNotificationSource()
            .any { it.key == key && NotificationCaptureService.isOwnPackage(it.packageName) }
        if (isOwn) {
            return ToolResult.Error(
                """{"success":false,"message":"Cannot dismiss Rouse Context notifications"}"""
            )
        }

        val success = notificationDismisser(key)
        return ToolResult.Success("""{"success":$success}""")
    }
}

internal class SearchNotificationHistoryTool(
    private val dao: NotificationDao,
    private val fieldEncryptor: FieldEncryptor?
) : McpTool() {
    override val name = "search_notification_history"
    override val description = "Searches the notification history log." +
        " Supports text search, package filter, and time range."

    val query by stringParam("query", "Text to search in title and body").optional()
    val packageFilter by stringParam("package", "Filter by package name").optional()
    val since by stringParam("since", "ISO datetime start bound").optional()
    val until by stringParam("until", "ISO datetime end bound").optional()
    val limit by intParam("limit", "Max results (default 50)").optional()

    override suspend fun execute(): ToolResult {
        val sinceMillis = since?.let { parseIsoToMillis(it) } ?: 0L
        val untilMillis = until?.let { parseIsoToMillis(it) } ?: Long.MAX_VALUE
        val lim = limit ?: NotificationMcpProvider.DEFAULT_SEARCH_LIMIT

        val records = dao.search(
            sinceMillis = sinceMillis,
            untilMillis = untilMillis,
            packageFilter = packageFilter,
            textQuery = query,
            limit = lim
        )

        val result = buildJsonArray {
            for (record in records) {
                add(recordToJson(record, fieldEncryptor))
            }
        }
        return ToolResult.Success(result.toString())
    }
}

internal class GetNotificationStatsTool(private val dao: NotificationDao) : McpTool() {
    override val name = "get_notification_stats"
    override val description = "Returns aggregate notification statistics for a time period." +
        " Includes total count, per-app breakdown, and busiest hour."

    val period by stringParam("period", "Time period: 'today', 'week', or 'month'").optional()

    override suspend fun execute(): ToolResult {
        val periodStr = period ?: "today"
        val (sinceMillis, untilMillis) = periodToRange(periodStr)

        val total = dao.countInRange(sinceMillis, untilMillis)
        val byApp = dao.countByPackage(sinceMillis, untilMillis)

        val mostFrequent = byApp.firstOrNull()?.packageName ?: "none"

        val result = buildJsonObject {
            put("total", JsonPrimitive(total))
            put(
                "by_app",
                buildJsonArray {
                    for (pc in byApp) {
                        add(
                            buildJsonObject {
                                put("package", JsonPrimitive(pc.packageName))
                                put("count", JsonPrimitive(pc.count))
                            }
                        )
                    }
                }
            )
            put("most_frequent_app", JsonPrimitive(mostFrequent))
            put("period", JsonPrimitive(periodStr))
        }
        return ToolResult.Success(result.toString())
    }
}

// ---------- formatting helpers ----------

private fun sbnToJson(sbn: StatusBarNotification) = buildJsonObject {
    put("key", JsonPrimitive(sbn.key))
    put("package", JsonPrimitive(sbn.packageName))
    val extras = sbn.notification.extras
    put("title", JsonPrimitive(extras.getCharSequence("android.title")?.toString() ?: ""))
    put("text", JsonPrimitive(extras.getCharSequence("android.text")?.toString() ?: ""))
    put("time", JsonPrimitive(sbn.postTime))
    put("ongoing", JsonPrimitive(sbn.isOngoing))
    put("category", JsonPrimitive(sbn.notification.category ?: ""))

    val actions = sbn.notification.actions
    put(
        "actions",
        buildJsonArray {
            if (actions != null) {
                for ((index, action) in actions.withIndex()) {
                    add(
                        buildJsonObject {
                            put("label", JsonPrimitive(action.title?.toString() ?: ""))
                            put("id", JsonPrimitive(index))
                        }
                    )
                }
            }
        }
    )
}

private fun recordToJson(record: NotificationRecord, fieldEncryptor: FieldEncryptor?) =
    buildJsonObject {
        put("id", JsonPrimitive(record.id))
        put("package", JsonPrimitive(record.packageName))
        val title = fieldEncryptor?.decrypt(record.title) ?: record.title
        val text = fieldEncryptor?.decrypt(record.text) ?: record.text
        put("title", JsonPrimitive(title ?: ""))
        put("text", JsonPrimitive(text ?: ""))
        put("time", JsonPrimitive(record.postedAt))
        put("removed_at", JsonPrimitive(record.removedAt ?: 0L))
        put("category", JsonPrimitive(record.category ?: ""))
        put("ongoing", JsonPrimitive(record.ongoing))
        put("actions_taken", JsonPrimitive(record.actionsTaken ?: "[]"))
    }

@Suppress("TooGenericExceptionCaught", "SwallowedException")
private fun parseIsoToMillis(iso: String): Long = try {
    Instant.parse(iso).toEpochMilli()
} catch (_: Exception) {
    try {
        LocalDate.parse(iso)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    } catch (_: Exception) {
        0L
    }
}

private fun periodToRange(period: String): Pair<Long, Long> {
    val now = Instant.now()
    val zone = ZoneId.systemDefault()
    val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant()
    val since = when (period) {
        "today" -> todayStart
        "week" -> todayStart.minus(7, ChronoUnit.DAYS)
        "month" -> todayStart.minus(30, ChronoUnit.DAYS)
        else -> todayStart
    }
    return since.toEpochMilli() to now.toEpochMilli()
}
