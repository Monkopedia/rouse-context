package com.rousecontext.integrations.notifications

import android.service.notification.StatusBarNotification
import com.rousecontext.mcp.core.McpServerProvider
import com.rousecontext.notifications.FieldEncryptor
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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
        registerListActive(server)
        registerPerformAction(server)
        registerDismiss(server)
        registerSearchHistory(server)
        registerGetStats(server)
    }

    private fun registerListActive(server: Server) {
        server.addTool(
            name = "list_active_notifications",
            description = "Returns currently posted notifications on the device.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put(
                        "filter",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive("Optional app package name pattern to filter by")
                            )
                        }
                    )
                },
                required = emptyList()
            )
        ) { request ->
            val filter = request.params.arguments?.get("filter")?.jsonPrimitive?.content

            val notifications = activeNotificationSource()
                .filter { sbn ->
                    filter == null || sbn.packageName.contains(filter, ignoreCase = true)
                }

            val result = buildJsonArray {
                for (sbn in notifications) {
                    add(sbnToJson(sbn))
                }
            }
            CallToolResult(content = listOf(TextContent(result.toString())))
        }
    }

    private fun registerPerformAction(server: Server) {
        server.addTool(
            name = "perform_notification_action",
            description = "Executes an action button on an active notification." +
                " Rouse Context's own notifications cannot be acted on.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put(
                        "notification_key",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive("The notification key from list_active_notifications")
                            )
                        }
                    )
                    put(
                        "action_index",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put(
                                "description",
                                JsonPrimitive("Zero-based index of the action to perform")
                            )
                        }
                    )
                },
                required = listOf("notification_key", "action_index")
            )
        ) { request ->
            if (!allowActions) {
                return@addTool CallToolResult(
                    content = listOf(TextContent(ERR_ACTIONS_DISABLED)),
                    isError = true
                )
            }

            val key = request.params.arguments?.get("notification_key")
                ?.jsonPrimitive?.content
                ?: return@addTool CallToolResult(
                    content = listOf(TextContent(ERR_MISSING_KEY)),
                    isError = true
                )

            // Block actions on our own notifications
            val isOwn = activeNotificationSource()
                .any { it.key == key && NotificationCaptureService.isOwnPackage(it.packageName) }
            if (isOwn) {
                val msg = """{"success":false,""" +
                    """"message":"Cannot act on Rouse Context notifications"}"""
                return@addTool CallToolResult(
                    content = listOf(TextContent(msg)),
                    isError = true
                )
            }

            val actionIndex = request.params.arguments?.get("action_index")
                ?.jsonPrimitive?.int
                ?: return@addTool CallToolResult(
                    content = listOf(TextContent(ERR_MISSING_ACTION)),
                    isError = true
                )

            val success = actionPerformer(key, actionIndex)
            val message = if (success) "Action performed" else "Failed to perform action"
            CallToolResult(
                content = listOf(
                    TextContent("""{"success":$success,"message":"$message"}""")
                )
            )
        }
    }

    private fun registerDismiss(server: Server) {
        server.addTool(
            name = "dismiss_notification",
            description = "Dismisses an active notification by its key." +
                " Rouse Context's own notifications cannot be dismissed.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put(
                        "notification_key",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive("The notification key to dismiss")
                            )
                        }
                    )
                },
                required = listOf("notification_key")
            )
        ) { request ->
            if (!allowActions) {
                return@addTool CallToolResult(
                    content = listOf(TextContent(ERR_ACTIONS_DISABLED)),
                    isError = true
                )
            }

            val key = request.params.arguments?.get("notification_key")?.jsonPrimitive?.content
                ?: return@addTool CallToolResult(
                    content = listOf(TextContent("""{"success":false}""")),
                    isError = true
                )

            // Block dismissing our own notifications
            val isOwn = activeNotificationSource()
                .any { it.key == key && NotificationCaptureService.isOwnPackage(it.packageName) }
            if (isOwn) {
                val msg = """{"success":false,""" +
                    """"message":"Cannot dismiss Rouse Context notifications"}"""
                return@addTool CallToolResult(
                    content = listOf(TextContent(msg)),
                    isError = true
                )
            }

            val success = notificationDismisser(key)
            CallToolResult(
                content = listOf(TextContent("""{"success":$success}"""))
            )
        }
    }

    private fun registerSearchHistory(server: Server) {
        server.addTool(
            name = "search_notification_history",
            description = "Searches the notification history log." +
                " Supports text search, package filter, and time range.",
            inputSchema = searchHistorySchema()
        ) { request ->
            handleSearchHistory(request.params.arguments ?: emptyMap())
        }
    }

    private suspend fun handleSearchHistory(args: Map<String, JsonElement>): CallToolResult {
        val textQuery = args["query"]?.jsonPrimitive?.content
        val packageFilter = args["package"]?.jsonPrimitive?.content
        val since = args["since"]?.jsonPrimitive?.content
            ?.let { parseIsoToMillis(it) } ?: 0L
        val until = args["until"]?.jsonPrimitive?.content
            ?.let { parseIsoToMillis(it) } ?: Long.MAX_VALUE
        val limit = args["limit"]?.jsonPrimitive?.int
            ?: DEFAULT_SEARCH_LIMIT

        val records = dao.search(
            sinceMillis = since,
            untilMillis = until,
            packageFilter = packageFilter,
            textQuery = textQuery,
            limit = limit
        )

        val result = buildJsonArray {
            for (record in records) {
                add(recordToJson(record))
            }
        }
        return CallToolResult(
            content = listOf(TextContent(result.toString()))
        )
    }

    private fun searchHistorySchema() = ToolSchema(
        properties = buildJsonObject {
            put("query", stringProp("Text to search in title and body"))
            put("package", stringProp("Filter by package name"))
            put("since", stringProp("ISO datetime start bound"))
            put("until", stringProp("ISO datetime end bound"))
            put(
                "limit",
                buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Max results (default 50)"))
                }
            )
        },
        required = emptyList()
    )

    private fun stringProp(description: String) = buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("description", JsonPrimitive(description))
    }

    private fun registerGetStats(server: Server) {
        server.addTool(
            name = "get_notification_stats",
            description = "Returns aggregate notification statistics for a time period." +
                " Includes total count, per-app breakdown, and busiest hour.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put(
                        "period",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive("Time period: 'today', 'week', or 'month'")
                            )
                        }
                    )
                },
                required = emptyList()
            )
        ) { request ->
            val period = request.params.arguments?.get("period")?.jsonPrimitive?.content ?: "today"
            val (sinceMillis, untilMillis) = periodToRange(period)

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
                put("period", JsonPrimitive(period))
            }
            CallToolResult(content = listOf(TextContent(result.toString())))
        }
    }

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

    private fun recordToJson(record: NotificationRecord) = buildJsonObject {
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

    companion object {
        internal val ISO_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ISO_INSTANT
        private const val DEFAULT_SEARCH_LIMIT = 50
        private const val ERR_MISSING_KEY =
            """{"success":false,"message":"Missing notification_key"}"""
        private const val ERR_MISSING_ACTION =
            """{"success":false,"message":"Missing action_index"}"""
        private const val ERR_ACTIONS_DISABLED =
            """{"success":false,"message":"Notification actions are disabled by the user."}"""
    }
}
