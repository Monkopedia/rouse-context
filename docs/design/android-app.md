# Android App Design

Reflects shipped behavior on `main`. Source paths and line numbers are cited so this doc can be re-validated against the tree.

## Architecture Overview

The Android app is a single-Activity Compose app that hosts an on-device MCP server. The app shell wires together a Koin graph, a Compose Navigation host, and a foreground tunnel service. MCP integrations register through a single contract (`McpIntegration`) and the app is the only module that knows about all the others.

Runtime topology of a live session:

```
AI client ──TLS──▶ relay (SNI passthrough) ──mux/WebSocket──▶
  TunnelForegroundService.collectIncomingSessions (in :work) ──▶
  SessionHandler.handleStream (in :core:bridge) ──▶
  McpSession (in :core:mcp) ──▶ McpIntegration.provider (one per integration)
```

`TunnelForegroundService` collects `TunnelClient.incomingSessions` itself and calls `sessionHandler.handleStream(stream)` directly, one child coroutine per stream (`work/src/main/kotlin/com/rousecontext/work/TunnelForegroundService.kt:254-279`).

That service collector is the **only** one. `:core:bridge` used to also hold a `TunnelSessionManager` that collected `incomingSessions` and dispatched to the same `SessionHandler`, in `jvmMain` — but no `main` source set ever constructed it and it never had a Koin binding, so it read as a second, competing production collector that was one wiring change away from being used, and its `catch (_: Exception)` documented a policy that ran nowhere. It was test scaffolding in a production source set; #671 deleted it and moved the collect-into-handler helper its tests needed to `core/bridge/src/jvmTest/.../SessionCollection.kt`. For where session errors actually surface, see [Session handling and failure discrimination](#session-handling-and-failure-discrimination).

Per-integration audit, notification, and permission state surfaces in the app UI; the integration itself only supplies metadata, an MCP `provider`, and an availability check.

## Module Map

Canonical list from `settings.gradle.kts:25-35`. Eleven modules; eight ship in the APK (`:core:testfixtures`, `:device-tests`, and `:e2e` are test-only).

| Module | Path | Role | Project deps |
|---|---|---|---|
| `:app` | `app/` | Single Activity, Koin graph, navigation, integration registry, all setup/manage screens. Only module that knows about every other module. | `:core:tunnel`, `:core:mcp`, `:core:bridge`, `:api`, `:integrations`, `:notifications`, `:work` |
| `:core:tunnel` | `core/tunnel/` | KMP. Mux protocol, WebSocket client, `CertificateStore`, `OnboardingFlow`, `CertProvisioningFlow`, `RelayApiClient`. No MCP knowledge. | (none) |
| `:core:mcp` | `core/mcp/` | KMP. `McpSession`, `McpServerProvider`, OAuth device-code flow, token store, HTTP routing, `AuditListener`. | (none) |
| `:core:bridge` | `core/bridge/` | KMP. Wires `:core:tunnel` mux streams to `:core:mcp` sessions: `SessionHandler`, `McpSessionFactory`, `HttpHeaderInjector`, `TlsCertProvider`. | `:core:tunnel`, `:core:mcp` |
| `:api` | `api/` | The `McpIntegration` interface plus the supporting `IntegrationStateStore` / `NotificationSettingsProvider` contracts. | `:core:mcp` |
| `:integrations` | `integrations/` | Hosts every shipped MCP server: `health`, `notifications`, `outreach`, `usage`. Each subpackage exports an `McpServerProvider` plus its own data layer. | `:core:mcp`, `:api`, `:notifications` |
| `:notifications` | `notifications/` | Notification channels, foreground notification builder, audit Room database, post-session decisioning. | `:core:tunnel`, `:core:mcp`, `:api` |
| `:work` | `work/` | `TunnelForegroundService`, `TunnelFailureReporting`, `GracefulTunnelShutdown`, push dispatch (`FcmDispatch` + `WakeDispatcher` — the `FirebaseMessagingService` itself lives in `:app`, see below), WorkManager workers (`CertRenewalWorker`, `SecurityCheckWorker`), `IntegrationSecretsSynchronizer`, `IdleTimeoutManager`, `WakelockManager`, `WakeReconnectDecider`, `SessionActivityTracker`. | `:api`, `:core:tunnel`, `:core:bridge`, `:notifications` |
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

`:core:bridge` exposes both of its dependencies with `api(...)` rather than `implementation(...)`, so `:core:mcp` types reach `:work` transitively even though `work/build.gradle.kts` does not name `:core:mcp`.

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
    suspend fun isUserEnabled(integrationId: String): Boolean
    suspend fun setUserEnabled(integrationId: String, enabled: Boolean)
    fun observeUserEnabled(integrationId: String): Flow<Boolean>
    suspend fun wasEverEnabled(integrationId: String): Boolean
    fun observeEverEnabled(integrationId: String): Flow<Boolean>
    fun observeChanges(): Flow<Unit>
}

interface NotificationSettingsProvider {
    suspend fun settings(): NotificationSettings
    // plus a reactive Flow view of the same settings
}
```

The active subdomain is *not* exposed as a flow. `CertificateStore` offers `suspend fun getSubdomain(): String?`, and per-integration URLs are built through `McpUrlProvider` (`app/src/main/java/com/rousecontext/app/UrlBuilder.kt`), a `:app`-owned wrapper whose `buildUrl(integrationId)` / `buildHostname(integrationId)` combine the stored subdomain with that integration's secret prefix. The MCP endpoint path is always `/mcp`, so the URL *path component* is not what selects an integration — the hostname is. `McpRouting.resolveIntegration` (`core/mcp/src/jvmMain/kotlin/com/rousecontext/mcp/core/McpRouting.kt:216-224`) parses the first `Host` label as `{secret}-{integration}` and hands the extracted name to `ProviderRegistry.providerForPath`, whose production map is keyed on `McpIntegration.path` with the leading `/` stripped (`app/src/main/java/com/rousecontext/app/registry/IntegrationProviderRegistry.kt:41`, consumed at `:77-81`). So `path` is still the routing key — changing an integration's `path` would break its routing; it is simply no longer consumed as a URL path component.

## Navigation

Single Activity, Compose Navigation. All routes are defined as constants in `Routes` (`app/src/main/java/com/rousecontext/app/ui/navigation/AppNavigation.kt:42-111`) and registered as composables in `AppNavigation()`.

| Route constant | Pattern | Purpose |
|---|---|---|
| `ONBOARDING` | `onboarding?autostart={autostart}` | Welcome screen + autostart trigger; same composable for both modes (see Onboarding below). |
| `ONBOARDING_BASE` | `onboarding` | NavHost start destination; resolves to the `ONBOARDING` composable because the arg is nullable. |
| `ONBOARDING_AUTOSTART` | `onboarding?autostart=true` | Concrete URL used by `NotificationPreferences` Continue (#392). |
| `NOTIFICATION_PREFERENCES` | `onboarding/notification_preferences` | Post-session mode picker, plus inline `POST_NOTIFICATIONS` permission request. |
| `BACKGROUND_DELIVERY` | `background_delivery?settings={settings}` | UnifiedPush distributor picker (#463). Registered unconditionally, navigated to only in the `foss` flavor. `BACKGROUND_DELIVERY_BASE` / `BACKGROUND_DELIVERY_SETTINGS` are the onboarding-step and Settings-entry URLs. |
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

Bottom nav: Home, Audit, Settings (3 tabs). The bottom bar and top bar are hidden during the onboarding routes — which include the two background-delivery routes (`ONBOARDING_ROUTES` at `AppNavigation.kt:113-119`, applied at `AppNavigation.kt:142-183`).

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

Drawn from `OnboardingViewModel.kt:67-138` and the destinations under `app/src/main/java/com/rousecontext/app/ui/navigation/destinations/`:

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

There is no separate "generating keys" UI step; key generation happens inside `CertProvisioningFlow` while the UI is in `ProvisioningCerts`. The two `OnboardingStep` values in `OnboardingViewModel.kt:39-42` are exhaustive.

The decision to run cert provisioning at Continue (rather than deferring to the first integration add) is logged in `docs/ux-decisions.md` under the 2026-04-24 entry.

Failure surfaces:

- `OnboardingState.RateLimited` — relay or ACME rate-limit; UI shows the formatted retry date.
- `OnboardingState.Failed` — terminal error with retry button; on cert-provisioning failures, `registrationStatus.markComplete()` still fires so a retry from Settings can re-run only the cert hop.

`OnboardingViewModel.startOnboarding()` launches on an `appScope` (Application-scoped) coroutine so the multi-second cert hop survives the user backgrounding the app or recomposition tearing down `viewModelScope`.

## Foreground Service & Tunnel Lifecycle (`:work`)

`:work` owns every Android-lifecycle concern around the tunnel. Bridging TLS and MCP is `:core:bridge`'s job, invoked from inside the service — but `:work` is not MCP-free: `TunnelForegroundService` injects `McpSession` and `ProviderRegistry`, `WakeDispatcher` gates on `ProviderRegistry.enabledPaths()`, `SessionActivityAuditListener` implements `:core:mcp`'s `AuditListener`, and `GracefulTunnelShutdown` takes an `McpSession`. Those types reach `:work` transitively through `:core:bridge`'s `api(project(":core:mcp"))`.

### `TunnelForegroundService`

- Started by `WakeDispatcher` on a `type: "wake"` push, and only when at least one integration is enabled.
- `startForeground()` is the first non-trivial call in `onCreate` (#325); a start blocked by the Android 15 `dataSync` budget posts an `FgsLimitNotifier` notification and stops the service, and `onTimeout()` does the same mid-run (#450, #451).
- Holds the singleton `TunnelClient` from Koin; calls `connect()`.
- Posts and updates the foreground notification as `TunnelState` changes, with defensive reconciles after `awaitReady()` and after a successful `connect()` (#510).
- Reconnects with exponential backoff on an unexpected `DISCONNECTED`, giving up after 5 minutes.
- Stops via `IdleTimeoutManager` after the configured idle window with no active streams.

### Session handling and failure discrimination

`collectIncomingSessions` (`TunnelForegroundService.kt:254-279`) collects `tunnelClient.incomingSessions`, launches one child coroutine per mux stream, and calls `sessionHandler.handleStream(stream)` inside a per-stream `try`. Catching per stream is what keeps one bad session from taking the tunnel down.

What happens to a throwable that escapes `handleStream` is decided by `work/src/main/kotlin/com/rousecontext/work/TunnelFailureReporting.kt`, which classifies into **three** kinds — not the binary "report it or swallow it" the older layout implied (#642, #650):

| Kind | What it means | What the boundary does |
|---|---|---|
| `Cancellation` | The scope is shutting down (service destroyed, idle timeout, tunnel teardown). | Rethrown, so the coroutine completes as cancelled. Never logged as an error, never reported. |
| `PeerOrTransport` | The peer, network, or relay did something ordinary — hung up, aborted mid-handshake, reset a stream. | `Log.i` with the exception class and message, so the *rate* stays visible in logcat. No crash report. |
| `Defect` | This layer reached a state it has no handling for. | `Log.e` **and** `CrashReporter.logCaughtException`. |

`classifyTunnelFailure` matches in this order:

1. `CancellationException` → `Cancellation`. It must come first: `CancellationException` extends `IllegalStateException`, so any later arm could otherwise swallow it.
2. `TunnelError.UnhandledTlsState` → `Defect`. By its own kdoc this is a defect in our TLS/mux code, not anything the peer did (#616).
3. `TunnelError.ConnectionFailed` → depends on the cause. `TunnelClientImpl.connect` wraps *anything* non-`TunnelError` that escapes it, so a `null` or `IOException` cause is routine (`PeerOrTransport`) while any other cause is a laundered defect and stays loud.
4. `TunnelError.TlsHandshakeFailed`, `WebSocketClosed`, `StreamRefused`, `StreamReset`, and plain `IOException` → `PeerOrTransport`.
5. Everything else → `Defect`.

The quiet set is a **closed allowlist** and the `else` arm is `Defect`, deliberately: an unanticipated throwable — the `IllegalStateException` from `SessionHandler.handleStream`'s missing-cert `error(...)`, an NPE, `TunnelError.ProtocolError`, `InternalError`, `CertificateError`, `InvalidStateTransition` — is by definition something this layer did not plan for, so the default has to be loud or the policy decays into "report nothing". `TunnelError` extends `Exception` rather than `IOException`, which is what keeps the `is IOException` arm from capturing a tunnel error.

The same policy is applied at the connect boundary: `connectToRelay`'s catch around `tunnelClient.connect(relayUrl)` calls `reportTunnelFailure` too, so a phone that woke with no network is quiet while a cert/provisioning bug is loud. Two nearby sites rethrow cancellation by hand for the same reason: the best-effort `disconnect()` inside `connectToRelay`, and the backoff loop in `launchReconnect` (which logs other attempt failures at `Log.w`, since the loop itself is the recovery).

Below this boundary, `SessionHandler`'s two copy loops rethrow `CancellationException` and `TunnelError.UnhandledTlsState` and treat every other exception as a quiet EOF (#616, #626, #630). `:core:bridge` is a KMP jvm target with no `Log` or `CrashReporter`, so propagating to `TunnelForegroundService` *is* how a defect down there becomes observable.

### Push dispatch (`FcmDispatch`, `WakeDispatcher`, `FcmReceiver`)

The receiver is flavor-specific; the routing is not.

- `FcmDispatch.resolve(data)` (in `:work`) is pure: it maps `type` to `FcmAction.StartService` / `EnqueueRenewal` / `Ignore`.
- `WakeDispatcher` (in `:work`, extracted in #463) executes the action. Before acting it calls `ProviderRegistry.awaitReadyBlocking(2s)` (#414) and drops the push if the registry never becomes ready or if no integration is enabled — avoiding pointless foreground-service starts and ACME quota burn. `wake` starts `TunnelForegroundService`; `renew` enqueues `CertRenewalWorker` with `ExistingWorkPolicy.KEEP`; unknown types are logged and ignored.
- `FcmReceiver`, the `FirebaseMessagingService` subclass, lives in **`app/src/google/java/com/rousecontext/app/push/FcmReceiver.kt`**, not `:work` — so the shared `:work` module links no `firebase-messaging` (#476). Its `onNewToken()` calls `FcmTokenRegistrar` (still in `:work`) to update the relay.
- The `foss` flavor's `UnifiedPushReceiver` (`app/src/foss/java/com/rousecontext/app/push/`) routes the identical relay payloads through the same `WakeDispatcher`.

### WorkManager workers

- **`CertRenewalWorker`** — periodic, daily, network-constrained. Reads cert expiry from `CertificateStore`; if the cert is expired or within `DEFAULT_RENEWAL_WINDOW_DAYS` (21) of expiry, calls `POST /renew` (mTLS while the cert is still valid, credential-signed once it has expired). Schedules backoff on failure; honors `retry_after` on `rate_limited`.
- **`SecurityCheckWorker`** — periodic self-check against the device's own cert and crt.sh. Persists results via `SecurityCheckPreferences`. Triggered by `SecurityCheckScheduler`.

### Wakelock and reconnect logic

- `WakelockManager` observes `TunnelClient.state`: CONNECTING and ACTIVE acquire `PARTIAL_WAKE_LOCK`; DISCONNECTING and DISCONNECTED release it immediately; CONNECTED schedules a release after a 3 s grace (`CONNECTED_GRACE_MS`), cancelled if ACTIVE arrives first — so the relay's first request frame after the handshake is not deferred by Doze on non-exempt devices. Idempotent: never double-acquires or double-releases.
- `WakeReconnectDecider` decides whether a `wake` FCM should reconnect immediately or be treated as spurious (`SpuriousWakeRecorder` keeps the rolling history).

### Integration secret synchronization

`IntegrationSecretsSynchronizer` keeps the device's stored integration secrets in sync with the relay's view. Run on connect and after `rotate-secret` events so a freshly-rotated integration secret on one device propagates without a full re-register.

### Idle timeout

`IdleTimeoutManager` (`work/src/main/kotlin/com/rousecontext/work/IdleTimeoutManager.kt`) arms the timer on each entry into CONNECTED and cancels it on each transition to ACTIVE. On expiry it invokes an injected `onTimeout`, which `AppModule` wires to `gracefulTunnelShutdown(mcpSession, tunnelClient)` rather than a bare `disconnect()`.

The duration is adaptive: a *substantive* wake cycle (one that issued at least one `tools/call`, tracked by `SessionActivityTracker`) gets the user-facing "Idle timeout" (`idle_timeout_minutes`, default 5); a discovery-only or spurious wake gets the much shorter "Quick disconnect" (`quick_disconnect_seconds`, default 30), so a lightweight wake does not hold the foreground service up and burn the Android 15 `dataSync` budget. The "disable timeout" toggle makes `timeoutProvider` return `null` so the timer never arms; it is gated on the device being battery-optimization-exempt. Completed wake cycles are reported to `SpuriousWakeRecorder`.

## Audit & Notifications (`:notifications`)

### Audit persistence

Room database, schema in `notifications/src/main/.../audit/`. `RoomAuditListener` implements `AuditListener` from `:core:mcp` so every tool call/response surfaces with timestamps, arguments JSON, result JSON, duration, session ID, and provider ID; `:work`'s `SessionActivityAuditListener` wraps it so each `tools/call` also marks the wake cycle substantive for the adaptive idle timeout. There is no automatic retention window: `AuditDao.deleteOlderThan` exists but the only production caller is the user-driven "clear history" action in `AuditHistoryViewModel`, and the UI says so ("Audit history is kept until you clear it manually").

Notification taps deep-link into `AUDIT?provider={id}&scrollToCallId={id}` so the user lands on the specific call.

### Notification channels

Eight channels, all created by `NotificationChannels.createAll()` (`notifications/src/main/.../NotificationChannels.kt`):

| Channel id | Name | Importance |
|---|---|---|
| `rouse_foreground` | Foreground Service | LOW |
| `rouse_session` | Session Activity | DEFAULT |
| `rouse_error` | Errors | HIGH |
| `rouse_alert` | Security Alerts | HIGH |
| `rouse_auth_request` | Authorization Requests | DEFAULT |
| `rouse_session_summary` | Session Summaries | LOW |
| `rouse_outreach_launch` | Outreach Launch Requests | DEFAULT |
| `rouse_fgs_limit` | Foreground Service Limit | HIGH |

The Session Summaries channel is the one controlled by the `post_session_mode` setting (`summary` / `each_usage` / `suppress`).

### Foreground notification builder

`ForegroundNotifier.build(context, message: String): Notification` — an object in `:notifications`, called directly by `TunnelForegroundService` rather than injected through Koin, because a foreground-service notification must be *returned* to `startForeground()` rather than posted. `TunnelForegroundService` maps `TunnelState` to the message string itself. Always posted while the service is running, regardless of `post_session_mode`.

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
- `single<AuditListener> { ... }` — `SessionActivityAuditListener` wrapping the Room-backed `RoomAuditListener`; consumed by `:core:mcp`.
- `single<TlsCertProvider> { CertStoreTlsCertProvider(...) }`, `single<McpSessionFactory> { SharedMcpSessionFactory(...) }`, and `single<SessionHandler> { SessionHandler(certProvider, mcpSessionFactory) }` — the `:core:bridge` trio the foreground service consumes. `SessionHandler` is the whole bridge wiring: `:core:bridge` exposes no collector to bind, because the only collector lives in `TunnelForegroundService` (see the Architecture Overview and #671).
- `single { IdleTimeoutManager(...) }`, `single { WakelockManager(...) }`, `single<SpuriousWakeRecorder> { ... }` — the `:work` collaborators the service injects.

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

`BatteryOptimization.isExempt()` wraps `PowerManager.isIgnoringBatteryOptimizations()`. When the device is not exempt *and* the active delivery transport needs wake-ups, `MainDashboardViewModel` surfaces a `BatteryOptimizationBanner` card on Home that deep-links to the system dialog; Settings carries the same action as a row. The banner is derived from live state on every emission — it has no dismissed flag persisted anywhere, and there is no OEM-specific (Samsung/Xiaomi/Huawei) guidance in the app.

### Security monitoring (Settings → Trust Status)

- Self-check timestamp + result (verified / warning / alert).
- CT-log (crt.sh) check timestamp + result.
- Truncated SHA-256 cert fingerprint (tap to expand).
- Overall status: green / amber / red.

Warning (amber) is non-blocking: shows "Unable to verify certificate — will retry." Alert (red) blocks new MCP sessions until acknowledged and offers View details + Rotate address actions.

Persistence: `SecurityCheckPreferences` in `:work` (DataStore-backed).

### Settings (DataStore)

Keys as they appear in the `*PreferencesKey` declarations, grouped by owning store.

`AppStatePreferences` (`:app`):

- `idle_timeout_minutes: Int` (default 5)
- `idle_timeout_disabled: Boolean` (battery-opt-exempt only)
- `quick_disconnect_seconds: Int` (default 30 — the non-substantive wake timeout)
- `ignore_daily_time_limit: Boolean`
- `security_check_interval_hours: Int`
- `has_launched_before: Boolean`

`DataStoreNotificationSettingsProvider` (`:app`):

- `post_session_mode: String` (`summary` | `each_usage` | `suppress`)
- `show_all_mcp_messages: Boolean`

`SecurityCheckPreferences` (`:work`):

- `last_check_time: Long`
- `self_cert_result: String` / `ct_log_result: String`
- `cert_fingerprint: String`
- per-source `warning_streak_*`, `notified_for_streak_*`, `last_streak_increment_*`

Also `ThemePreference` (`theme_mode`), `CertRenewalPreferences` (`last_attempt_time`, `last_outcome`), `SpuriousWakePreferences` (`total_wake_count`, `spurious_wake_count_total`, `spurious_wake_timestamps`), and per-integration keys under `IntegrationSettingsStore` / `DataStoreIntegrationStateStore`.

### Subdomain rotation

In Settings: "Generate new address" button. Confirmation warns "All connected clients will lose access. Once per 30 days." On confirm, `POST /register` with `force_new: true`; old subdomain invalidated, all tokens revoked, certs re-provisioned, UI updates.

### ACME rate-limit UX

When the relay returns `rate_limited` for cert issuance: notification "Certificate issuance delayed. Will retry on [date].", onboarding shows the same retry date, `CertRenewalWorker` schedules retry honoring `retry_after`.

## Still Needs Design

- **Third-party provider discovery** — bound-service intent filter, verification, trust UI. Not v1.
