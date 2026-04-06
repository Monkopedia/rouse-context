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

---

# Blockers for FCM Wake Flow (April 2026)

## Summary

The FCM wake flow was tested end-to-end on a Pixel 2 XL (API 30) with
package `com.rousecontext.debug`. The flow starts correctly but the WebSocket
connection to the relay is rejected with HTTP 401 because the device cannot
present a valid mTLS client certificate.

## What works

1. **ADB test broadcast**: `TestWakeReceiver` correctly receives the broadcast
   and starts `TunnelForegroundService`.
2. **Foreground service**: The service creates successfully, shows a foreground
   notification, and attempts to connect via `TunnelClientImpl.connect()`.
3. **Koin DI**: All dependencies (TunnelClient, WakelockManager,
   IdleTimeoutManager, relayUrl) resolve correctly at runtime.
4. **Relay connectivity**: The device can reach `relay.rousecontext.com:443`
   and the TLS handshake to the relay completes (the 401 comes at HTTP level,
   not TLS level, because the relay uses `allow_unauthenticated()` for TLS).
5. **Onboarding flow**: The UI correctly detects missing subdomain and presents
   the onboarding screen. Tapping "Get Started" triggers the full flow:
   Firebase anonymous auth, FCM token retrieval, CSR generation, and relay
   `/register` call. The relay responds with a subdomain.

## Blockers

### 1. Relay ACME client is stubbed (relay-side)

**File**: `relay/src/main.rs` line 95

```rust
let acme: Arc<dyn rouse_relay::acme::AcmeClient> = Arc::new(StubAcme);
```

The production relay at `relay.rousecontext.com` uses `StubAcme`, which returns
`"stub-cert"` instead of a real PEM certificate chain. This means:

- The device's `rouse_cert.pem` file contains the literal string `stub-cert`
- There is no valid X.509 certificate for the device to present during mTLS
- The relay's `/ws` handler checks for a `DeviceIdentity` extension (extracted
  from the client cert CN/SAN) and returns 401 when none is present

**To fix**: Wire up a real ACME client (Let's Encrypt + Cloudflare DNS-01) in
the relay. The `AcmeClient` trait already exists in `relay/src/acme.rs`; a
real implementation needs to be built and connected in `main.rs`.

### 2. mTLS HttpClient was not configured (FIXED in this branch)

**Before this fix**: `TunnelClientImpl` used a bare `HttpClient` with no client
certificate configuration. Even with a valid cert on disk, it would never be
presented during the TLS handshake.

**After this fix**: The Koin module creates an `HttpClient` via
`MtlsHttpClientFactory` which loads the device cert from `rouse_cert.pem` and
the private key from Android Keystore, then configures the Ktor CIO engine's
TLS `certificates` list with a `CertificateAndKey`.

## ADB test command

```bash
# Force-stop ensures fresh Koin initialization
adb shell am force-stop com.rousecontext.debug

# Send wake broadcast (must include type=wake extra)
adb shell am broadcast \
  -n com.rousecontext.debug/com.rousecontext.app.debug.TestWakeReceiver \
  -a com.rousecontext.action.TEST_WAKE \
  --es type wake

# Watch logs
adb logcat -s TestWakeReceiver:* TunnelForegroundService:* MtlsHttpClientFactory:*
```

## Observed log sequence (with fix, stub cert)

```
MtlsHttpClientFactory: Certificate chain is empty
MtlsHttpClientFactory: No device cert available, creating plain HttpClient
TunnelForegroundService: TunnelForegroundService created
TunnelForegroundService: Tunnel disconnected, stopping service
TunnelForegroundService: Failed to connect to relay
  ConnectionFailed: Handshake exception, expected status code 101 but was 401
TunnelForegroundService: TunnelForegroundService destroyed
```

## Next steps

1. Implement real ACME cert issuance in the relay (wire `AcmeClient` trait
   to Let's Encrypt + Cloudflare DNS-01)
2. Re-onboard the device to get a real certificate
3. Re-test the wake flow -- with a valid cert, `MtlsHttpClientFactory` will
   configure the HttpClient and the relay should accept the mTLS connection
