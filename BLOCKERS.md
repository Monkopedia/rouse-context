# Blockers for T-12: Health Connect MCP Server

## Skipped: HealthConnectIntegration (McpIntegration interface)

The task calls for a `HealthConnectIntegration` class implementing `McpIntegration` from an
`:api` module. That module does not exist in the codebase yet -- there is no
`McpIntegration`, `IntegrationState`, or `IntegrationStateStore` interface to implement.

**What was built instead:** The `HealthConnectMcpServer` directly implements
`McpServerProvider` from `:mcp-core`, which is the existing contract. When the `:api`
module lands, `HealthConnectIntegration` can be added as a thin adapter.

**Skipped test:** `HealthConnectIntegrationTest` -- nothing to test without the interface.

## Skipped: ktlint / detekt verification

The `ktlintCheck`, `ktlintFormat`, and `detekt` Gradle tasks referenced in `CLAUDE.md`
are not configured in the project (no plugin applied in `build.gradle.kts`). Code follows
the style conventions manually but cannot be machine-verified.

## Skipped: Real HealthConnectClient implementation

`HealthConnectRepository` is defined as an interface with a test fake. The production
implementation wrapping `androidx.health.connect.client.HealthConnectClient` requires
an Android `Context` and cannot be unit tested without Robolectric or instrumentation tests.
It should be implemented when the `:app` wiring is built.

---

# Blockers for Device Integration Tests

## Status after fixes

| Test | Status | Notes |
|------|--------|-------|
| `relay status shows zero connections when idle` | FIXED | Was SocketException, now passes |
| `apk builds and installs with custom relay config` | FIXED | Was build failure, now passes |
| `full wake flow - device connects to relay after wake broadcast` | BLOCKED | App cannot complete the flow yet |

## What was fixed

### relay status shows zero connections when idle

**Root cause:** The relay routes ALL connections via SNI parsing from TLS ClientHello.
When the relay runs without TLS (empty cert_path), plain HTTP requests like `GET /status`
have no ClientHello, so `parse_sni` returns `None` and the connection is `Reject`ed.

**Fix:** In `relay/src/main.rs`, when `tls_config` is `None` and the routing decision is
`Reject`, re-route to `RelayApi` instead. This allows plain HTTP to reach the axum router.

### APK build failures (affected 2 tests)

Three issues prevented APK builds from inside the test harness:

1. **ANDROID_HOME not set** in `ApkBuilder` subprocess environment.
   Fixed in `device-tests/.../ApkBuilder.kt`.

2. **Debug signing keystore missing** in worktree. The `.signing/` directory is gitignored
   and wasn't present. Fixed by symlinking from the main repo (manual step, not code).

3. **Wrong package name** in `DeviceController` and `FakeFcmServer`. The debug APK has
   `applicationIdSuffix = ".debug"`, making the package `com.rousecontext.debug`, but the
   test code used `com.rousecontext`.
   Fixed in `DeviceController.kt` (package name + activity component) and `FakeFcmServer.kt`.

## What remains blocked

### full wake flow - device connects to relay after wake broadcast

The test sends an ADB broadcast simulating FCM, expects the app to start a tunnel
connection to the local relay, then checks `/status` for `active_mux_connections > 0`.

**The app cannot complete this flow because the DI wiring is incomplete:**

1. `TunnelForegroundService` declares `lateinit var tunnelClient: TunnelClient` (and
   `wakelockManager`, `idleTimeoutManager`) that must be injected by the `:app` module.
   The `app/src/.../di/AppModule.kt` does not provide these bindings.

2. When `FcmReceiver.onMessageReceived()` calls `startForegroundService(Intent(...,
   TunnelForegroundService::class.java))`, the service will crash in `onStartCommand`
   when accessing the uninitialized `tunnelClient`.

3. Even if injection worked, `TunnelForegroundService.relayUrl` defaults to
   `wss://relay.rousecontext.com/ws` -- the test relay runs plain HTTP on localhost.
   The `BuildConfig.RELAY_HOST`/`RELAY_PORT` values are set at build time but nothing
   reads them into the service's `relayUrl` field.

**To unblock this test, the following are needed:**

- Wire `TunnelClient`, `WakelockManager`, and `IdleTimeoutManager` in `AppModule.kt`
- Set `TunnelForegroundService.relayUrl` from `BuildConfig.RELAY_HOST`/`BuildConfig.RELAY_PORT`
- Handle the ws:// vs wss:// scheme based on whether TLS is configured
- Ensure the `TunnelForegroundService` is declared in a manifest (currently the
  `:work` module's `AndroidManifest.xml` is empty)
