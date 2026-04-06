# Notifications Integration

## Overview
Surfaces device notifications to AI clients via MCP. Two modes:
- **Active**: Current notifications on the device (with actions)
- **History**: Room-backed searchable log with configurable retention

## Android Requirements
- `NotificationListenerService` — requires explicit user grant in Settings > Apps > Special access > Notification access
- OEMs aggressively kill this service; need to detect and re-prompt
- Must filter out our own audit/tunnel notifications to avoid recursion

## MCP Tools

### `list_active_notifications`
Returns currently posted notifications.
- **Params**: `filter` (optional, app package pattern)
- **Returns**: Array of `{id, package, title, text, time, actions: [{label, id}], ongoing, category}`
- Excludes notifications from `com.rousecontext.*`

### `perform_notification_action`
Executes an action on an active notification.
- **Params**: `notification_id`, `action_id`
- **Returns**: `{success, message}`
- Must NOT allow actions on our own audit notifications

### `dismiss_notification`
Dismisses a notification.
- **Params**: `notification_id`
- **Returns**: `{success}`

### `search_notification_history`
Queries Room-backed notification history.
- **Params**: `query` (text search), `package` (filter), `since` (ISO datetime), `until` (ISO datetime), `limit` (default 50)
- **Returns**: Array of `{id, package, title, text, time, actions_taken: [{label, time}]}`

### `get_notification_stats`
Summary of notification patterns.
- **Params**: `period` (e.g. "today", "week", "month")
- **Returns**: `{total, by_app: [{package, count}], busiest_hour, most_frequent_app}`

## Data Model (Room)

```
@Entity
data class NotificationRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val title: String?,
    val text: String?,
    val postedAt: Long,       // epoch millis
    val removedAt: Long?,     // when dismissed/cleared
    val category: String?,
    val ongoing: Boolean,
    val actionsTaken: String? // JSON array of {label, time}
)
```

## Setup Flow
1. Integration card shows "Requires Notification Access"
2. Tap → opens system Settings notification access page
3. On return, verify permission granted
4. Configure retention period (1 day, 1 week, 1 month, 3 months, forever)
5. Background: `NotificationListenerService` starts capturing

## Architecture
- `notifications/` module gets the `NotificationListenerService` and Room DAO
- `NotificationMcpProvider` implements `McpServerProvider` in a new `:notification-mcp` or within `:notifications`
- Service writes to Room on every `onNotificationPosted` / `onNotificationRemoved`
- Periodic cleanup job deletes records older than retention period
