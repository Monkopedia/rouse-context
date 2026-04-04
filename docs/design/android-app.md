# Android App Design

## Module Structure

```
:core:tunnel         — KMP (jvm + android), mux protocol, CertificateStore interface, TunnelClient
:core:mcp            — KMP (jvm + android), McpSession, ProviderRegistry, TokenStore, OAuth, HTTP routing
:api                 — provider registration contract: McpIntegration interface, UI contracts
:health              — implements :api for Health Connect
:notifications       — NotificationModel state machine, audit persistence, notification channels,
                       provides createForegroundNotification() for :work
:work                — foreground service, WorkManager cert renewal, FCM receiver, wakelock management
:app                 — Compose shell, Koin graph, navigation, registers providers, ties everything together
```

### Dependency Graph

```
:core:tunnel         ← (no project module deps)
:core:mcp            ← (no project module deps)
:api                 ← :core:mcp
:health              ← :api, :core:mcp
:notifications       ← :core:tunnel, :core:mcp
:work                ← :core:tunnel, :notifications (for foreground notification creation)
:app                 ← :core:tunnel, :core:mcp, :api, :health, :notifications, :work
```

## :api — Provider Registration Contract

Defines how MCP provider modules integrate with the app. Each provider implements `McpIntegration`.

```kotlin
interface McpIntegration {
    /** Unique ID, e.g. "health" */
    val id: String

    /** Display name, e.g. "Health Connect" */
    val displayName: String

    /** Short description for the Add Integration picker */
    val description: String

    /** URL path, e.g. "/health" */
    val path: String

    /** The MCP server provider for tool/resource registration */
    val provider: McpServerProvider

    /** Is the underlying platform available? (e.g. Health Connect installed) */
    suspend fun isAvailable(): Boolean

    /**
     * Register this integration's screens into the nav graph.
     * Routes should be relative — the app provides a prefix (e.g. "integration/health/")
     * to ensure uniqueness.
     */
    fun NavGraphBuilder.registerNavigation(prefix: String, navController: NavController)

    /** Relative route for onboarding flow (appended to prefix) */
    val onboardingRoute: String    // e.g. "setup"

    /** Relative route for settings/detail screen (appended to prefix) */
    val settingsRoute: String      // e.g. "settings"
}
```

Note: `isEnabled()` removed. Enabled/disabled state is a user toggle managed by `IntegrationStateStore`, not a platform check. Permission issues are surfaced by the integration on its own settings screen (banners, grant buttons) and through MCP error responses.

Each integration owns its own screens entirely — layout, navigation within its routes, permission handling. The app navigates to `integration/{id}/{onboardingRoute}` or `integration/{id}/{settingsRoute}` and the integration handles the rest.

The app provides utility composables for common UI elements (URL bar, disable button) that integrations can optionally use for consistency, but the integration controls its own layout.

Uses Compose Navigation 3 (Nav3).

### Supporting interfaces in :api

```kotlin
/** User-toggled enable/disable per integration. Backed by Preferences DataStore. */
interface IntegrationStateStore {
    fun isUserEnabled(integrationId: String): Boolean
    fun setUserEnabled(integrationId: String, enabled: Boolean)
    fun observeUserEnabled(integrationId: String): Flow<Boolean>
}

/** Notification preference access for NotificationModel. */
interface NotificationSettingsProvider {
    val settings: NotificationSettings
}
```

The subdomain is injected as `StateFlow<String?>` via Koin — integrations use it to build their full URL for display. No wrapper interface needed.

## :health — Health Connect Integration

Implements `McpIntegration`:
- `isAvailable()` → checks Health Connect SDK is installed
- `requiredPermissions()` → READ_STEPS, READ_HEART_RATE, READ_SLEEP, etc.
- `onboardingRoute` = "setup" → full screen explaining data exposure, requests Health Connect permissions
- `settingsRoute` = "settings" → shows granted/available permissions with grant buttons, recent activity for this provider, disable button
- `provider` → `HealthConnectMcpServer` which calls `server.addTool()` / `server.addResource()`

Owns its own screens via `registerNavigation()`. Routes: `integration/health/setup`, `integration/health/settings`.

Depends on: `:api`, `:core:mcp`, Health Connect SDK.

## :work — Android Tunnel Integration

Manages the Android lifecycle around `:core:tunnel`. Does NOT know about MCP.

### Foreground Service
- Started by FCM receiver on `type: "wake"`
- Holds reference to TunnelClient singleton (from Koin)
- Calls `tunnelClient.connect()` on start
- Posts foreground notification via `createForegroundNotification()` from `:notifications`
- Updates notification as TunnelState changes
- Stops when tunnel disconnects and idle timeout elapses

### Wakelock Management
- Observes `TunnelClient.state` from the singleton
- ACTIVE (1+ streams): acquire PARTIAL_WAKE_LOCK
- CONNECTED (no streams): release wakelock
- CONNECTING: hold wakelock for Doze window
- DISCONNECTED: release

### Idle Timeout
- Configurable (2-5 min default), read from Preferences DataStore
- Timer starts when state transitions from ACTIVE → CONNECTED
- Cancelled if new stream arrives
- On expiry: calls `tunnelClient.disconnect()`
- "Disable timeout" option only available if battery optimization exempted

### FCM Receiver
- `FirebaseMessagingService` subclass
- Dispatches by `type`:
  - `"wake"` → start foreground service
  - `"renew"` → enqueue WorkManager cert renewal job
  - unknown → log, ignore
- `onNewToken()` → update Firestore

### WorkManager Cert Renewal
- Periodic: once daily, network constraint
- Checks `CertificateStore.getCertExpiry()`
- If <14 days remaining: call `POST /renew` (mTLS if cert valid, Firebase+signature if expired)
- On failure: exponential backoff retry
- On `rate_limited`: schedule retry for `retry_after`

### Testing
- Service lifecycle testable via Robolectric or instrumented tests
- WorkManager testable via `TestWorkerBuilder`
- Wakelock logic testable by observing `TunnelState` emissions (mock TunnelClient)

## :notifications — Notification & Audit

### NotificationModel (pure state machine)

```kotlin
sealed interface SessionEvent {
    data object MuxConnected : SessionEvent
    data object MuxDisconnected : SessionEvent
    data class StreamOpened(val streamId: Int, val integration: String) : SessionEvent
    data class StreamClosed(val streamId: Int) : SessionEvent
    data class ToolCallCompleted(val streamId: Int, val toolName: String) : SessionEvent
    data class ErrorOccurred(val streamId: Int?, val error: TunnelError) : SessionEvent
}

sealed interface NotificationAction {
    data class ShowForeground(val message: String) : NotificationAction
    data object DismissForeground : NotificationAction
    data class PostSummary(val toolCallCount: Int, val sessionId: String) : NotificationAction
    data class PostToolUsage(val toolName: String, val sessionId: String) : NotificationAction
    data class PostWarning(val message: String, val sessionId: String) : NotificationAction
    data class PostError(val message: String, val details: String) : NotificationAction
}

data class NotificationSettings(
    val postSessionMode: PostSessionMode,  // SUMMARY, EACH_USAGE, SUPPRESS
    val notificationPermissionGranted: Boolean,
)

class NotificationModel(private val settingsProvider: NotificationSettingsProvider) {
    fun onEvent(event: SessionEvent): List<NotificationAction>
}
```

Pure function: events + settings → notification actions. Fully unit-testable.

A thin Android adapter in this module maps `NotificationAction` to `NotificationManager` calls.

### Foreground Notification

```kotlin
fun createForegroundNotification(state: TunnelState, activeStreams: Int): Notification
```

Called by `:work`'s foreground service. Returns a `Notification` object. The `:work` module gets this via Koin injection.

### Notification Channels
- **Active Session** — foreground service, ongoing
- **Session Summary** — post-session summaries, controllable by setting
- **Warnings/Errors** — escalating severity

### Audit Persistence

Room database:

```
audit_log
  id: Long (auto-increment)
  timestamp: Long
  tool_name: String
  arguments_json: String
  result_json: String
  duration_ms: Long
  session_id: String     // UUID from MuxStream.sessionId
  provider_id: String
```

Implements `AuditListener` from `:core:mcp`. Retention: 30 days, pruned on app launch.

### Deep-links
Notification taps deep-link into audit history filtered by session ID. Uses Compose Navigation deep-link support.

## :app — Shell

### Responsibilities
- Koin module definitions (wires all dependencies)
- Single Activity + Compose Navigation
- Screen orchestration (onboarding, main, settings, integration setup, audit, authorized clients)
- Registers all `McpIntegration` implementations with `ProviderRegistry`
- Creates the shared `McpSession` singleton
- Observes tunnel events and feeds them to `NotificationModel`
- Binds `CertificateStore` implementation (reads PEM files, accesses Keystore)

### Koin Graph (key bindings)
```kotlin
val appModule = module {
    // Core singletons
    single { TunnelClientImpl(get<CertificateStore>()) } bind TunnelClient::class
    single { McpSession(get(), get(), get()) }
    single { NotificationModel(get<NotificationSettingsProvider>()) }

    // Interfaces → implementations
    single<CertificateStore> { FileCertificateStore(get()) }
    single<TokenStore> { RoomTokenStore(get()) }
    single<IntegrationStateStore> { DataStoreIntegrationStateStore(get()) }
    single<NotificationSettingsProvider> { DataStoreNotificationSettingsProvider(get()) }
    single<AuditListener> { RoomAuditListener(get()) }
    single<ProviderRegistry> {
        IntegrationProviderRegistry(getAll<McpIntegration>(), get<IntegrationStateStore>())
    }

    // Subdomain as StateFlow for URL display
    single<StateFlow<String?>> { get<CertificateStore>().subdomainFlow() }

    // Provider registrations
    single<McpIntegration> { HealthConnectIntegration(get()) }
    // Future: single<McpIntegration> { NotificationsIntegration() }
}
```

### Navigation

Single Activity, Compose Navigation 3 (Nav3):

App-owned routes:
- `/welcome` — first-run welcome screen
- `/main` — dashboard (connection status, enabled integrations, recent activity)
- `/add` — add integration picker
- `/setup/notifications` — notification preferences (first integration only)
- `/setup/certprogress` — cert issuance spinner
- `/setup/ready/{id}` — integration URL + waiting for client
- `/approve` — device code approval (full screen)
- `/approved` — connection confirmed
- `/audit` — audit history list, filterable
- `/audit/{sessionId}` — audit detail for a session (deep-link target from notifications)
- `/clients` — authorized clients list with revoke
- `/settings` — app settings

Integration-owned routes (registered via `McpIntegration.registerNavigation()`):
- `/integration/{id}/*` — each integration registers its own routes under this prefix
- e.g. `/integration/health/setup` — Health Connect onboarding
- e.g. `/integration/health/settings` — Health Connect detail/settings

### Dependency Injection
- Koin (not Hilt) for simplicity and KMP compatibility
- All core interfaces bound in the app module
- Integration modules provide their `McpIntegration` implementations via Koin

### Battery Optimization
- On launch: check `PowerManager.isIgnoringBatteryOptimizations()`
- If not exempt: show card on main screen, deep-link to system dialog
- Don't nag — remember dismissal in DataStore
- OEM-specific guidance for Samsung/Xiaomi/Huawei

## Device Onboarding

First-run flow before any integrations:

1. Welcome screen — explain what the app does
2. Notification permission (Android 13+) — request `POST_NOTIFICATIONS`
   - Granted: normal behavior
   - Denied: force post_session_notifications to "Suppress", inform user
   - Foreground service notification works regardless
3. Device registration progress — generating keys... registering... issuing certificate...
4. Success — show assigned subdomain, guide to adding first integration
5. Failure — error with retry, no partial state

## Integration Management

### Integration States

Derived from `IntegrationStateStore` + `TokenStore`:

- **Available** — `!userEnabled`, never set up. Shows in Add picker.
- **Disabled** — `!userEnabled`, was previously set up. Shows in Add picker (re-enable skips setup if permissions still granted).
- **Pending** — `userEnabled`, `!tokenStore.hasTokens(id)`. No client authorized yet. Shows on dashboard.
- **Active** — `userEnabled`, `tokenStore.hasTokens(id)`. At least one client authorized. Shows on dashboard.
- **Unavailable** — `!isAvailable()`. Platform not present. Greyed out in Add picker.

State transitions:
```
Available ──[setup flow]──→ Pending ──[client authorizes]──→ Active
Disabled  ──[re-enable]───→ Pending ──[client authorizes]──→ Active
Active    ──[user disables]──→ Disabled
Pending   ──[user disables]──→ Disabled
Active    ──[all tokens revoked]──→ Pending
```

`clientAuthorized` is updated explicitly: set to true when device code approval completes for this integration, derived from `tokenStore.hasTokens(integrationId)`.

### Setup Flow
1. User taps integration from the Add picker
2. Notification preferences shown (first integration only, skipped if already set)
3. App navigates to `integration/{id}/setup` (integration owns the screen)
4. Integration handles permissions, explanation, etc.
5. On complete: `IntegrationStateStore.setUserEnabled(id, true)`, navigate to cert spinner (if needed) then URL screen
6. On cancel: back to Add picker, state unchanged

### Main Screen
- Connection status indicator
- Integration list with state badges
- "Add Integration" for available-but-not-enabled integrations
- Recent audit activity summary

## Client Authorization UI

- Device code approval: notification → open app → enter code → approve/deny
- Authorized clients screen (`/clients`): list with client_id, created_at, last_used_at
- Revoke per client
- All tokens revoked on subdomain rotation

## Subdomain Rotation

In Settings:
- "Generate new address" button
- Confirmation: "All connected clients will lose access. Once per 30 days."
- On confirm: `/register` with `force_new: true`
- Old subdomain invalidated, tokens revoked, UI updates

## ACME Rate Limit UX

When relay returns `rate_limited`:
- Notification: "Certificate issuance delayed. Will retry on [date]."
- Onboarding shows waiting state
- WorkManager retry scheduled

## Settings (Preferences DataStore)

- `idle_timeout_minutes: Int` (default 5)
- `idle_timeout_disabled: Boolean` (requires battery optimization exempt)
- `post_session_notifications: String` ("summary" | "each_usage" | "suppress")
- `battery_optimization_dismissed: Boolean`
- `notification_permission_denied: Boolean`

## Still Needs Design

1. **Third-party provider discovery** — bound service intent filter, verification, trust UI (future, not v1)
