# Android App Design

## Overview

The `:app` module is the shell that wires tunnel, mcp-core, and mcp-health together. It owns the UI, foreground service, audit logging, and user-facing onboarding experience.

## Responsibilities

- **Foreground service** during active MCP sessions
- **Session wiring**: receives `MuxStream` from tunnel, creates `McpSession`, connects them
- **Audit logging**: implements `AuditListener`, persists per-tool-call history
- **Onboarding UI**: guides user through first-run setup, shows assigned subdomain
- **Settings**: idle timeout, notification preferences, server enable/disable
- **Trust UI**: shows active sessions, tool call history, permission management

## Session Wiring

When the tunnel emits an incoming session:

```kotlin
tunnelClient.incomingSessions.collect { stream ->
    val session = McpSession(
        providers = listOf(healthConnectServer, /* future providers */),
        auditListener = auditLogger,
    )
    // Each session runs in its own coroutine, scoped to the foreground service
    serviceScope.launch(Dispatchers.IO) {
        try {
            session.run(stream.input, stream.output)
        } finally {
            stream.close()
        }
    }
}
```

Multiple concurrent sessions are supported (one per MuxStream).

## Foreground Service

### Lifecycle
- Started by TunnelService on FCM wakeup (or manual connect)
- Shows persistent notification while mux connection is active
- Notification updates with active session count
- Stops when mux disconnects and all sessions are torn down

### Notification Strategy

Three notification channels with distinct purposes:

**Channel: Active Session** (foreground service, ongoing)
- Shown while mux connection is active
- Content: "Rouse is providing context from your phone to AI"
- Actions: "Disconnect"
- Dismissed automatically when mux disconnects

**Channel: Session Summary** (post-session)
- Posted after session ends (all streams closed, mux disconnects)
- Controlled by user setting: `post_session_notifications`
  - **"Summary"** (default): "10 tool usages made" — single notification per session
  - **"Each usage"**: individual notification per tool call during the session
  - **"Suppress"**: no post-session notifications
- Tapping the notification deep-links to audit history for that session

**Channel: Warnings/Errors** (important)
- "Roused with no usage" — FCM woke device, session opened, but no tool calls made before disconnect
- "A problem occurred providing context" — stream error, auth failure, unexpected disconnect
- Escalating severity: informational warnings are silent, errors use default importance
- Tapping deep-links to audit history with error details

### Notification Model (testable)

Notification decisions are driven by a pure model, decoupled from Android's `NotificationManager`:

```kotlin
// Events the notification model consumes
sealed interface SessionEvent {
    data object MuxConnected : SessionEvent
    data object MuxDisconnected : SessionEvent
    data class StreamOpened(val streamId: Int, val integration: String) : SessionEvent
    data class StreamClosed(val streamId: Int) : SessionEvent
    data class ToolCallCompleted(val streamId: Int, val toolName: String) : SessionEvent
    data class ErrorOccurred(val streamId: Int?, val error: TunnelError) : SessionEvent
}

// Outputs the model produces
sealed interface NotificationAction {
    data class ShowForeground(val message: String) : NotificationAction
    data object DismissForeground : NotificationAction
    data class PostSummary(val toolCallCount: Int, val sessionId: String) : NotificationAction
    data class PostToolUsage(val toolName: String, val sessionId: String) : NotificationAction
    data class PostWarning(val message: String, val sessionId: String) : NotificationAction
    data class PostError(val message: String, val details: String) : NotificationAction
}

// Pure function: (events, settings) → notification actions
class NotificationModel(private val settings: NotificationSettings) {
    fun onEvent(event: SessionEvent): List<NotificationAction>
}
```

The `NotificationModel` is a pure state machine — takes events and settings, produces notification actions. Fully unit-testable without Android. A thin Android adapter maps `NotificationAction` to actual `NotificationManager` calls.

## Wakelock Management

App observes `TunnelClient.state` and manages wakelocks accordingly:

- **ACTIVE** (1+ streams): hold `PARTIAL_WAKE_LOCK` — CPU must stay awake for tool call processing
- **CONNECTED** (mux open, no streams): release wakelock, let device sleep. Mux WebSocket may die — that's fine, next client triggers FCM again.
- **CONNECTING**: hold wakelock during connect window (must complete before Doze kills network)
- **DISCONNECTED**: no wakelock

Idle timeout (configurable, 2-5 min default): how long to stay in CONNECTED before the app tells tunnel to disconnect. Pure app concern — tunnel just exposes state. "Disable idle timeout" option available only if battery optimization is ignored (checked via `PowerManager.isIgnoringBatteryOptimizations()`).

## Battery Optimization

- On app launch, check `PowerManager.isIgnoringBatteryOptimizations()`
- If not exempted: show a card explaining why it matters ("Ensures your phone wakes reliably when a client connects")
- Button launches `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` system dialog
- Don't nag — show once, remember dismissal, resurface in Settings screen only
- Note: Samsung/Xiaomi/Huawei have additional proprietary kill switches beyond stock Android. We can't deep-link to those reliably, but we can detect the manufacturer and show OEM-specific guidance text (e.g. "On Samsung, also disable battery optimization in Device Care → Battery")

## Audit Logging

### AuditListener Implementation
```kotlin
class AuditLogger(private val dao: AuditDao) : AuditListener {
    override fun onToolCall(event: ToolCallEvent) {
        dao.insert(event.toEntity())
    }
}
```

### Storage
Room database with a single table:

```
audit_log
  id: Long (auto-increment)
  timestamp: Long
  tool_name: String
  arguments_json: String
  result_json: String
  duration_ms: Long
  session_id: String     // which MuxStream this came from
  provider_id: String    // which McpServerProvider handled it
```

Retention: keep last 30 days, prune on app launch.

### UI
- Scrollable list of recent tool calls
- Each entry: timestamp, tool name, duration, expand for arguments/result
- Filter by provider, date range
- Clear history option

## Device Onboarding

First-run flow before any integrations can be added:

1. Welcome screen — explain what the app does
2. Notification permission (Android 13+) — request `POST_NOTIFICATIONS`
   - Explain why: "Rouse Context notifies you when AI clients connect and summarizes what data was accessed"
   - If granted: proceed normally
   - If denied: force `post_session_notifications` setting to "Suppress" and inform user that session notifications are disabled. User can re-enable later in system settings + app settings.
   - Note: foreground service notification works regardless of this permission
3. Onboarding progress — generating keys... registering... issuing certificate...
4. Success — show assigned subdomain (e.g. `brave-falcon.rousecontext.com`)
5. If failure — show error with retry button, no partial state

Integration-specific permissions (Health Connect, etc.) are requested when the user enables each integration, not during device onboarding.

## Integration Management

Each `McpServerProvider` is an "integration" the user can enable independently. Each integration has its own URL path on the device's subdomain.

### Integration Registry

```kotlin
data class Integration(
    val id: String,                    // e.g. "health"
    val displayName: String,           // e.g. "Health Connect"
    val path: String,                  // e.g. "/health"
    val provider: McpServerProvider,
    val permissionsRequired: List<String>,
)

// Hardcoded for now. Future: dynamic discovery via bound service protocol.
val availableIntegrations = listOf(
    Integration(
        id = "health",
        displayName = "Health Connect",
        path = "/health",
        provider = HealthConnectMcpServer(healthConnectClient),
        permissionsRequired = listOf(
            "android.permission.health.READ_STEPS",
            "android.permission.health.READ_HEART_RATE",
            "android.permission.health.READ_SLEEP",
        ),
    ),
    // Future: notifications, contacts, etc.
)
```

### Integration Setup Flow

1. User taps "Add Integration" → sees list of available integrations
2. User selects one (e.g. Health Connect)
3. App explains what data will be exposed, requests necessary permissions
4. On permission grant: integration enabled
5. App shows the integration URL: `https://brave-falcon.rousecontext.com/health`
6. User adds URL to their MCP client (copy button, share sheet)
7. First connection from the MCP client triggers device code auth (if not already authorized)
8. Integration is live

### Integration States

- **Available** — not set up yet, permissions not granted
- **Enabled** — permissions granted, URL active, accepting connections
- **Disabled** — user toggled off, path returns error, permissions may still be granted

### Main Screen (post-onboarding)

- Device subdomain displayed prominently
- Connection status (disconnected / connected / N active sessions)
- List of integrations with status (available / enabled / disabled)
- Each enabled integration shows its URL, session count, recent activity
- "Add Integration" button
- Settings and authorized clients accessible

## Client Authorization UI

Device code flow (RFC 8628) approval happens in the app:

- Push notification when a new client requests authorization: "A client wants to connect"
- User opens app → enters the user code displayed by the MCP client → taps Approve
- Authorized clients screen: list of tokens with client_id, created_at, last_used_at
- Revoke button per client
- Auth is per-device, not per-integration — one approval covers all enabled integrations
- Token storage: Room database

## Health Connect Integration

### Permissions
Requested when user enables the Health Connect integration:
- `READ_STEPS`
- `READ_HEART_RATE`
- `READ_SLEEP`
- Additional types added as `:mcp-health` expands

### Permission Rationale
Android requires a rationale activity for Health Connect. The app declares this in the manifest and shows why each permission is needed.

## Dependencies

- `:tunnel` — provides `TunnelClient`, `MuxStream`
- `:mcp-core` — provides `McpSession`, `McpServerProvider`, `AuditListener`, HTTP routing
- `:mcp-health` — provides `HealthConnectMcpServer`
- `androidx.room` — audit log + token persistence
- `androidx.datastore` — preferences (settings)
- `androidx.lifecycle` — service lifecycle, viewmodel
- `androidx.activity:activity-compose` — Compose activity
- `androidx.compose.*` — UI framework
- `androidx.navigation:navigation-compose` — screen navigation

## Subdomain Rotation

Available in Settings:
- "Generate new address" button
- Confirmation dialog: "All connected clients will lose access. You'll need to re-add the new URL to your MCP clients. You can only do this once every 30 days."
- On confirm: calls `POST /register` with `force_new: true` + Firebase token + signature
- Old subdomain invalidated immediately, all client tokens revoked
- App updates stored subdomain + cert, shows new URLs
- Cooldown enforced client-side and server-side (30 days)

## ACME Rate Limit UX

When relay returns `rate_limited`:
- Notification: "Certificate issuance temporarily delayed. Will retry automatically on [date]."
- Onboarding flow shows waiting state with retry date
- WorkManager schedules retry for `retry_after` time
- App remains functional for any already-certified integrations (only affects new registrations/rotations)

## Still Needs Design

1. **Third-party provider discovery** — bound service intent filter, verification, trust UI (future, not v1)
