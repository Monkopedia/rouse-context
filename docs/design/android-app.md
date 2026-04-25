# Android App Design

Reflects shipped behavior on `main`. Source paths and line numbers are cited so this doc can be re-validated against the tree.

## Architecture Overview

The Android app is a single-Activity Compose app that hosts an on-device MCP server. The app shell wires together a Koin graph, a Compose Navigation host, and a foreground tunnel service. MCP integrations register through a single contract (`McpIntegration`) and the app is the only module that knows about all the others.

Runtime topology of a live session:

```
AI client ──TLS──▶ relay (SNI passthrough) ──mux/WebSocket──▶
  TunnelForegroundService (in :work) ──▶ TunnelSessionManager (in :core:bridge) ──▶
  McpSession (in :core:mcp) ──▶ McpIntegration.provider (one per integration)
```

Per-integration audit, notification, and permission state surfaces in the app UI; the integration itself only supplies metadata, an MCP `provider`, and an availability check.

## Module Map

Canonical list from `settings.gradle.kts:25-35`. Eleven modules; nine ship in the APK (`:core:testfixtures`, `:device-tests`, and `:e2e` are test-only).

| Module | Path | Role | Project deps |
|---|---|---|---|
| `:app` | `app/` | Single Activity, Koin graph, navigation, integration registry, all setup/manage screens. Only module that knows about every other module. | `:core:tunnel`, `:core:mcp`, `:core:bridge`, `:api`, `:integrations`, `:notifications`, `:work` |
| `:core:tunnel` | `core/tunnel/` | KMP. Mux protocol, WebSocket client, `CertificateStore`, `OnboardingFlow`, `CertProvisioningFlow`, `RelayApiClient`. No MCP knowledge. | (none) |
| `:core:mcp` | `core/mcp/` | KMP. `McpSession`, `McpServerProvider`, OAuth device-code flow, token store, HTTP routing, `AuditListener`. | (none) |
| `:core:bridge` | `core/bridge/` | KMP. Wires `:core:tunnel` mux streams to `:core:mcp` sessions: `TunnelSessionManager`, `SessionHandler`, `McpSessionFactory`, `HttpHeaderInjector`, `TlsCertProvider`. | `:core:tunnel`, `:core:mcp` |
| `:api` | `api/` | The `McpIntegration` interface plus the supporting `IntegrationStateStore` / `NotificationSettingsProvider` contracts. | `:core:mcp` |
| `:integrations` | `integrations/` | Hosts every shipped MCP server: `health`, `notifications`, `outreach`, `usage`. Each subpackage exports an `McpServerProvider` plus its own data layer. | `:core:mcp`, `:api`, `:notifications` |
| `:notifications` | `notifications/` | Notification channels, foreground notification builder, audit Room database, post-session decisioning. | `:core:tunnel`, `:core:mcp`, `:api` |
| `:work` | `work/` | `TunnelForegroundService`, FCM receiver + dispatch, WorkManager workers (`CertRenewalWorker`, `SecurityCheckWorker`), `IntegrationSecretsSynchronizer`, `IdleTimeoutManager`, `WakelockManager`, `WakeReconnectDecider`. | `:api`, `:core:tunnel`, `:core:bridge`, `:notifications` |
| `:core:testfixtures` | `core/testfixtures/` | Test-only. `TestRelayFixture` for booting the real relay binary in integration tests. | (test) |
| `:device-tests` | `device-tests/` | Instrumented tests. | (test) |
| `:e2e` | `e2e/` | Cold-start and end-to-end harnesses. | (test) |

### Dependency graph

Edges below are derived from the `build.gradle.kts` file in each module.

```
:core:tunnel    (no project deps)
:core:mcp       (no project deps)
:core:bridge    ──▶ :core:tunnel, :core:mcp
:api            ──▶ :core:mcp
:integrations   ──▶ :core:mcp, :api, :notifications
:notifications  ──▶ :core:tunnel, :core:mcp, :api
:work           ──▶ :api, :core:tunnel, :core:bridge, :notifications
:app            ──▶ :core:tunnel, :core:mcp, :core:bridge, :api,
                    :integrations, :notifications, :work
```

There is no separate `:health`, `:outreach`, `:usage`, or `:notifications-mcp` module; all four MCP integrations live as subpackages of `:integrations` (`integrations/src/main/{java,kotlin}/com/rousecontext/integrations/{health,notifications,outreach,usage}`).

## Integration Contract

The single contract is `McpIntegration` in `api/src/main/kotlin/com/rousecontext/api/McpIntegration.kt:11-45`:

```kotlin
interface McpIntegration {
    val id: String              // e.g. "health"
    val displayName: String     // e.g. "Health Connect"
    val description: String     // shown in the Add picker
    val path: String            // URL path prefix, e.g. "/health"
    val provider: McpServerProvider
    suspend fun isAvailable(): Boolean
    val onboardingRoute: String // legacy field (see below)
    val settingsRoute: String   // legacy field (see below)
}
```

What's *not* on this interface (and previous revisions of this doc claimed):

- No `registerNavigation(NavGraphBuilder, ...)`. Integrations do not own nav graph entries. Setup and manage screens for every integration live in `:app`'s nav graph (`HealthConnectSetupDestination`, `NotificationSetupDestination`, `OutreachSetupDestination`, `UsageSetupDestination`, `IntegrationManageDestination`).
- No `requiredPermissions()`. Permissions are integration-specific. Health Connect derives its set from `RecordTypeRegistry.allPermissions` (`integrations/src/main/java/com/rousecontext/integrations/health/RecordTypeRegistry.kt:336`); the others compute their own.
- `onboardingRoute` / `settingsRoute` survive on the interface but are not used to route — the app navigates by integration `id` to fixed routes (see Navigation below).

The four `McpIntegration` implementations live in `:app` (not `:integrations`) so that the app can wire `Context`, settings stores, scopes, and notifiers without `:integrations` taking a dependency on `:app`-owned types:

- `app/src/main/java/com/rousecontext/app/registry/HealthConnectIntegration.kt`
- `app/src/main/java/com/rousecontext/app/registry/NotificationIntegration.kt`
- `app/src/main/java/com/rousecontext/app/registry/OutreachIntegration.kt`
- `app/src/main/java/com/rousecontext/app/registry/UsageIntegration.kt`

Each delegates to the corresponding `*McpProvider`/`*McpServer` in `:integrations`.

### Supporting interfaces in `:api`

```kotlin
interface IntegrationStateStore {
    fun isUserEnabled(integrationId: String): Boolean
    fun setUserEnabled(integrationId: String, enabled: Boolean)
    fun observeUserEnabled(integrationId: String): Flow<Boolean>
}

interface NotificationSettingsProvider {
    val settings: NotificationSettings
}
```

The active subdomain is exposed as a `StateFlow<String?>` from `CertificateStore` and bound directly in Koin — integrations consume it for URL display without a wrapper interface.

## Navigation

Single Activity, Compose Navigation. All routes are defined as constants in `Routes` (`app/src/main/java/com/rousecontext/app/ui/navigation/AppNavigation.kt:40-96`) and registered as composables in `AppNavigation()`.

| Route constant | Pattern | Purpose |
|---|---|---|
| `ONBOARDING` | `onboarding?autostart={autostart}` | Welcome screen + autostart trigger; same composable for both modes (see Onboarding below). |
| `ONBOARDING_BASE` | `onboarding` | NavHost start destination; resolves to the `ONBOARDING` composable because the arg is nullable. |
| `ONBOARDING_AUTOSTART` | `onboarding?autostart=true` | Concrete URL used by `NotificationPreferences` Continue (#392). |
| `NOTIFICATION_PREFERENCES` | `onboarding/notification_preferences` | Post-session mode picker, plus inline `POST_NOTIFICATIONS` permission request. |
| `HOME` | `home` | Main dashboard. |
| `AUDIT` | `audit?provider={provider}&scrollToCallId={scrollToCallId}` | Audit list, optionally filtered + scrolled. |
| `AUDIT_DETAIL` | `audit_detail/{entryId}` | Single audit row detail. |
| `SETTINGS` | `settings` | App settings, trust status, subdomain rotation. |
| `ADD_INTEGRATION` | `add_integration` | Picker for integrations not yet enabled. |
| `INTEGRATION_MANAGE` | `integration/{integrationId}` | Per-integration manage screen: URL, recent activity, authorized clients, disable. |
| `INTEGRATION_SETUP` | `integration_setup/{integrationId}` | Cert/wiring spinner shown after a fresh enable, before the integration-specific setup. |
| `HEALTH_CONNECT_SETUP` | `health_connect_setup/{mode}` | Health Connect permission + record type picker. |
| `NOTIFICATION_SETUP` | `notification_setup/{mode}` | Notifications-MCP setup. |
| `OUTREACH_SETUP` | `outreach_setup/{mode}` | Outreach (installed-apps) setup. |
| `USAGE_SETUP` | `usage_setup/{mode}` | Usage stats setup. |
| `INTEGRATION_ENABLED` | `integration_enabled/{integrationId}` | Confirmation screen showing the URL + waiting-for-client state. |
| `AUTH_APPROVAL` | `auth_approval` | OAuth device-code approve/deny. |
| `ALL_CLIENTS` | `all_clients/{integrationId}` | Authorized clients list, per integration. |

`{mode}` on the four `*_setup` routes is a `SetupMode` enum (initial onboarding vs. post-onboarding management).

There are no integration-owned routes. Each `*_setup` destination is registered in `:app` (`app/src/main/java/com/rousecontext/app/ui/navigation/destinations/`) and the integration only supplies an `id` plus an `McpServerProvider`.

Bottom nav: Home, Audit, Settings (3 tabs). The bottom bar and top bar are hidden during the onboarding routes (`AppNavigation.kt:98-153`).

## Device Onboarding

Three relay hops, one Compose flow, one shared `OnboardingViewModel`.

### Relay sequence

`OnboardingFlow.execute()` (`core/tunnel/src/jvmMain/kotlin/com/rousecontext/tunnel/OnboardingFlow.kt:53-61`) chains:

1. `POST /request-subdomain` — relay reserves a single-word subdomain keyed by the Firebase UID (short TTL).
2. `POST /register` — consumes the reservation, returns the assigned subdomain plus the per-integration secret map. Subdomain + secrets are persisted via `CertificateStore`.
3. `POST /register/certs` (via `CertProvisioningFlow`) — mints the ACME server cert (DNS-01 through Cloudflare) and the relay-CA client cert. Added in #389 so a device never lands in a half-configured "subdomain but no certs" state.

Failure semantics:

- Step 1 failure: no persisted state. Reservation expires on its own.
- Step 2 failure: no persisted state.
- Step 3 failure: subdomain + secrets stay (#163). The user can retry just the cert hop without burning a new subdomain reservation.

### UI flow

Drawn from `OnboardingViewModel.kt:69-140` and the destinations under `app/src/main/java/com/rousecontext/app/ui/navigation/destinations/`:

```
Welcome  ──▶  NotificationPreferences  ──▶  onboarding?autostart=true  ──▶  Home
(ONBOARDING)   (NOTIFICATION_PREFERENCES)   (ONBOARDING_AUTOSTART)
```

Step-by-step:

1. **Welcome** (`OnboardingDestination`) — first-run intro. On Get Started, navigates to `NOTIFICATION_PREFERENCES`.
2. **Notification preferences** (`NotificationPreferencesDestination`) — pick post-session mode (summary / each-usage / suppress), and on Android 13+ inline-prompt for `POST_NOTIFICATIONS`. On Continue, navigates to `ONBOARDING_AUTOSTART` (popping `ONBOARDING` inclusive).
3. **Autostart re-entry** — the same `OnboardingDestination` recomposes with `autostart=true` and triggers `OnboardingViewModel.startOnboarding()` *on the destination's own VM*. This is the #392 invariant: previously two separate `OnboardingViewModel` instances existed (one for Welcome, one for NotificationPreferences) which caused the Welcome screen to never observe the relay registration completing. There is now exactly one `OnboardingViewModel` for the whole flow.
4. **Registering** — `OnboardingState.InProgress(Registering)` while Firebase anon auth + FCM token + `POST /request-subdomain` + `POST /register` run. UI shows a spinner with "Registering" copy.
5. **Provisioning certificates** — `OnboardingState.InProgress(ProvisioningCerts)` while `POST /register/certs` runs (multi-second ACME hop). UI flips to "Provisioning certificates" copy.
6. **Onboarded** — navigates to `HOME`.

There is no separate "generating keys" UI step; key generation happens inside `CertProvisioningFlow` while the UI is in `ProvisioningCerts`. The two `OnboardingStep` values in `OnboardingViewModel.kt:40-43` are exhaustive.

The decision to run cert provisioning at Continue (rather than deferring to the first integration add) is logged in `docs/ux-decisions.md` under the 2026-04-24 entry.

Failure surfaces:

- `OnboardingState.RateLimited` — relay or ACME rate-limit; UI shows the formatted retry date.
- `OnboardingState.Failed` — terminal error with retry button; on cert-provisioning failures, `registrationStatus.markComplete()` still fires so a retry from Settings can re-run only the cert hop.

`OnboardingViewModel.startOnboarding()` launches on an `appScope` (Application-scoped) coroutine so the multi-second cert hop survives the user backgrounding the app or recomposition tearing down `viewModelScope`.

## Foreground Service & Tunnel Lifecycle (`:work`)

`:work` owns every Android-lifecycle concern around the tunnel. It does not know about MCP — that's `:core:bridge`'s job, invoked from inside the service.

### `TunnelForegroundService`

- Started by `FcmReceiver` on `type: "wake"`.
- Holds the singleton `TunnelClient` from Koin; calls `connect()`.
- Posts the foreground notification via the builder from `:notifications`.
- Updates the notification as `TunnelState` changes.
- Stops via `IdleTimeoutManager` after the configured idle window with no active streams.

### FCM dispatch (`FcmDispatch`, `FcmReceiver`)

`FirebaseMessagingService` subclass dispatches by `type`:

- `wake` → start `TunnelForegroundService`.
- `renew` → enqueue `CertRenewalWorker`.
- unknown → log + ignore.

`onNewToken()` triggers `FcmTokenRegistrar` to update the relay.

### WorkManager workers

- **`CertRenewalWorker`** — periodic, daily, network-constrained. Reads cert expiry from `CertificateStore`; if <14 days left, calls `POST /renew` (mTLS if cert valid, Firebase-signature otherwise). Schedules backoff on failure; honors `retry_after` on `rate_limited`.
- **`SecurityCheckWorker`** — periodic self-check against the device's own cert and crt.sh. Persists results via `SecurityCheckPreferences`. Triggered by `SecurityCheckScheduler`.

### Wakelock and reconnect logic

- `WakelockManager` observes `TunnelClient.state`: ACTIVE (>=1 stream) holds `PARTIAL_WAKE_LOCK`; CONNECTED idle releases; CONNECTING holds for the Doze window; DISCONNECTED releases.
- `WakeReconnectDecider` decides whether a `wake` FCM should reconnect immediately or be treated as spurious (`SpuriousWakeRecorder` keeps the rolling history).

### Integration secret synchronization

`IntegrationSecretsSynchronizer` keeps the device's stored integration secrets in sync with the relay's view. Run on connect and after `rotate-secret` events so a freshly-rotated integration secret on one device propagates without a full re-register.

### Idle timeout

`IdleTimeoutManager` (`work/src/main/kotlin/com/rousecontext/work/IdleTimeoutManager.kt`) arms the timer when `TunnelClient.state` enters CONNECTED and fires `tunnelClient.disconnect()` on expiry. The timeout duration (default 5 min) and the "disable timeout" toggle are user-facing settings; the toggle is gated on the device being battery-optimization-exempt.

## Audit & Notifications (`:notifications`)

### Audit persistence

Room database, schema in `notifications/src/main/.../audit/`. Implements `AuditListener` from `:core:mcp` so every tool call/response surfaces with timestamps, arguments JSON, result JSON, duration, session ID, and provider ID. Retained for 30 days; pruned on app launch.

Notification taps deep-link into `AUDIT?provider={id}&scrollToCallId={id}` so the user lands on the specific call.

### Notification channels

- **Active Session** — foreground service, ongoing.
- **Session Summary** — controllable via `post_session_notifications` setting (`summary` / `each_usage` / `suppress`).
- **Warnings/Errors** — escalating severity, including security-check alerts.

### Foreground notification builder

`createForegroundNotification(state: TunnelState, activeStreams: Int): Notification` is provided to `:work` via Koin. Always posted while the service is running, regardless of `post_session_notifications` mode.

## Cross-Cutting Concerns

### Koin DI

The Koin graph is assembled in `app/src/main/java/com/rousecontext/app/di/AppModule.kt`. The four `McpIntegration` instances are registered as named singles and aggregated into a `List<McpIntegration>`:

```kotlin
single<McpIntegration>(named("health"))        { HealthConnectIntegration(androidContext()) }
single<McpIntegration>(named("outreach"))      { OutreachIntegration(...) }
single<McpIntegration>(named("notifications")) { NotificationIntegration(...) }
single<McpIntegration>(named("usage"))         { UsageIntegration(androidContext()) }

single<List<McpIntegration>> {
    buildList {
        add(get(named("notifications")))
        add(get(named("outreach")))
        add(get(named("usage")))
        add(get(named("health")))
        getKoin().getOrNull<McpIntegration>(named("test"))?.let { add(it) }
    }
}
```

Other key bindings:

- `single<TunnelClient> { ... }` — the singleton consumed by `:work`.
- `single<CertificateStore> { ... }` — file/Keystore-backed.
- `single<TokenStore> { ... }` — Room-backed.
- `single<IntegrationStateStore> { DataStoreIntegrationStateStore(...) }`.
- `single<NotificationSettingsProvider> { DataStoreNotificationSettingsProvider(...) }`.
- `single<AuditListener> { ... }` — Room-backed; consumed by `:core:mcp`.
- `single { TunnelSessionManager(...) }` — bridges tunnel mux streams to MCP sessions (`:core:bridge`).

### Integration state machine

Derived from `IntegrationStateStore` and `TokenStore`:

- **Available** — `!userEnabled`, never set up. Shows in Add picker.
- **Disabled** — `!userEnabled`, previously set up. Shows in Add picker.
- **Pending** — `userEnabled`, no tokens. Shown on dashboard.
- **Active** — `userEnabled`, ≥1 token. Shown on dashboard.
- **Unavailable** — `!isAvailable()`. Greyed out.

Transitions:

```
Available ──[setup]────────▶ Pending ──[client authorizes]──▶ Active
Disabled  ──[re-enable]────▶ Pending ──[client authorizes]──▶ Active
Active    ──[user disable]─▶ Disabled
Pending   ──[user disable]─▶ Disabled
Active    ──[tokens revoked]▶ Pending
```

### Setup flow (post-onboarding)

1. Add picker → user taps an available integration.
2. App navigates to `INTEGRATION_SETUP/{id}` (cert/wiring spinner; cert provisioning already ran during onboarding, so this is mostly an integration-specific bootstrap).
3. App navigates to the integration-specific setup destination (`HEALTH_CONNECT_SETUP`, `NOTIFICATION_SETUP`, `OUTREACH_SETUP`, or `USAGE_SETUP`) with `mode = Setup`.
4. On complete: `IntegrationStateStore.setUserEnabled(id, true)`, then `INTEGRATION_ENABLED/{id}` shows the URL and waits for the first client.
5. On cancel: back to Add picker.

### Battery optimization

On launch, `PowerManager.isIgnoringBatteryOptimizations()` is checked. If false, a card on Home deep-links to the system dialog. Dismissal is remembered in `AppStatePreferences`. OEM-specific guidance (Samsung/Xiaomi/Huawei) lives in the same flow.

### Security monitoring (Settings → Trust Status)

- Self-check timestamp + result (verified / warning / alert).
- CT-log (crt.sh) check timestamp + result.
- Truncated SHA-256 cert fingerprint (tap to expand).
- Overall status: green / amber / red.

Warning (amber) is non-blocking: shows "Unable to verify certificate — will retry." Alert (red) blocks new MCP sessions until acknowledged and offers View details + Rotate address actions.

Persistence: `SecurityCheckPreferences` in `:work` (DataStore-backed).

### Settings (DataStore)

- `idle_timeout_minutes: Int` (default 5)
- `idle_timeout_disabled: Boolean` (battery-opt-exempt only)
- `post_session_notifications: String` (`summary` | `each_usage` | `suppress`)
- `battery_optimization_dismissed: Boolean`
- `notification_permission_denied: Boolean`
- `last_self_check_time: Long` / `last_self_check_result: String`
- `last_ct_check_time: Long` / `last_ct_check_result: String`
- `cert_fingerprint: String`

### Subdomain rotation

In Settings: "Generate new address" button. Confirmation warns "All connected clients will lose access. Once per 30 days." On confirm, `POST /register` with `force_new: true`; old subdomain invalidated, all tokens revoked, certs re-provisioned, UI updates.

### ACME rate-limit UX

When the relay returns `rate_limited` for cert issuance: notification "Certificate issuance delayed. Will retry on [date].", onboarding shows the same retry date, `CertRenewalWorker` schedules retry honoring `retry_after`.

## Still Needs Design

- **Third-party provider discovery** — bound-service intent filter, verification, trust UI. Not v1.
