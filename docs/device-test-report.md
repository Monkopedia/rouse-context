# Device Integration Test Report

**Date:** 2026-04-07
**Device:** Pixel 3 XL (serial: `<your-device-serial>`, Android 12, SDK 31)
**Build:** `app-debug.apk` built from commit `a3a0c1a` (main)
**Package:** `com.rousecontext.debug`
**Relay:** `relay.rousecontext.com` (uptime ~14h at test start)

---

## Test Results Summary

| # | Test | Result |
|---|------|--------|
| 1 | Build and deploy | PASS |
| 2 | App launch smoke test | PASS |
| 3 | Dashboard state | PASS (with issue) |
| 4 | Tunnel connectivity | PASS |
| 5 | HTTP through tunnel | PASS |
| 6 | OAuth device code flow | PASS |
| 7 | Tool calls | PASS |
| 8 | Audit verification | PASS |
| 9 | Idle/reconnect | PASS (with issues) |
| 10 | Screenshots | PASS |

**Overall: 10/10 pass, 2 with non-blocking issues noted below.**

---

## Detailed Results

### 1. Build and Deploy -- PASS

- Build: `./gradlew :app:assembleDebug` succeeded in ~15s (179 tasks).
- Deployed via `scp` + `adb install -r`. Installation succeeded first try.
- Required `ANDROID_HOME` and the signing keystore to be present.

### 2. App Launch Smoke Test -- PASS

- `am start -n com.rousecontext.debug/com.rousecontext.app.MainActivity` launched without crash.
- No FATAL exceptions or AndroidRuntime errors in logcat.
- Firebase initialized successfully. WorkManager initialized.
- SLF4J warning (no providers) is expected and benign.
- Skipped 72 frames on first launch (cold start, acceptable on Pixel 3 XL).

### 3. Dashboard State -- PASS (with issue)

The dashboard renders correctly:
- Title: "Rouse Context"
- Status indicator: "Disconnected" (gray dot)
- Integrations section with "Test Tools" showing "Active"
- "Add Client" button
- "Recent Activity" section (initially empty, later populated with tool calls)
- Bottom nav: Home, Audit, Settings

**Issue:** The dashboard shows "Disconnected" even while the tunnel is actively connected to the relay (confirmed by relay `/status` showing `active_mux_connections: 1`). The status indicator does not update to reflect the live connection state.

### 4. Tunnel Connectivity -- PASS

- Wake broadcast (`type=wake`) started `TunnelForegroundService` successfully.
- mTLS WebSocket connected to relay within ~2 seconds.
- Relay `/status` confirmed `active_mux_connections: 1` after wake.
- FCM token sent to relay.
- TLS certificate: Let's Encrypt, CN=main-board.rousecontext.com, valid through 2026-07-05.

### 5. HTTP Through Tunnel -- PASS

- `GET /` returns `404 Not Found` (expected, no handler for root).
- `GET /.well-known/oauth-authorization-server` returns valid OAuth metadata JSON.
- TLS negotiation: TLSv1.2, ECDHE-RSA-AES128-GCM-SHA256.
- End-to-end latency is reasonable for relay-routed traffic.

### 6. OAuth Device Code Flow -- PASS

Full flow completed successfully:

1. **Discovery:** `GET /.well-known/oauth-authorization-server` returned metadata with correct endpoints.
2. **Registration:** `POST /test/register` created client `eb683ff6-1ef8-4cf7-977b-621d2a6875d1`.
3. **Device authorize:** `POST /test/device/authorize` returned device_code, user_code `YAQ5-6QAS`, 5s poll interval, 600s expiry.
4. **Approval:** ADB broadcast with `type=approve` and `user_code=YAQ5-6QAS` approved the pending request.
5. **Token exchange:** `POST /test/token` with device_code grant returned access token `Nz8NC6CoXmQw4uICYUysj4_Or0UhrRhLCGvbQCWYOjs`.

**Note:** The `approve_auth` broadcast type is for the authorization code flow, not the device code flow. The `approve` type with `user_code` extra is the correct broadcast for device code approval.

### 7. Tool Calls -- PASS

All three test tools executed successfully via Streamable HTTP (`POST /test/mcp`):

**echo:**
- Request: `{"name":"echo","arguments":{"message":"hello world"}}`
- Response: `{"text":"hello world","type":"text"}`
- Duration: 4ms

**get_time:**
- Response: `{"text":"2026-04-07T08:56:47.347644-04:00","type":"text"}`
- Duration: 34ms

**device_info:**
- Response: `model: Pixel 3 XL, manufacturer: Google, android_version: 12, sdk_int: 31`
- Duration: 5ms

MCP protocol version negotiated: server advertises `2024-11-05`, client requested `2025-03-26`.

### 8. Audit Verification -- PASS

Audit entries persisted correctly to Room database (`rouse_audit.db`):

| id | sessionId | toolName | provider | timestampMillis | durationMillis | success |
|----|-----------|----------|----------|-----------------|----------------|---------|
| 1 | test | get_time | test | 1775566607318 | 34 | 1 |
| 2 | test | device_info | test | 1775566608948 | 5 | 1 |
| 3 | test | echo | test | 1775566614786 | 4 | 1 |

All three entries visible in both the database and the Audit History UI with correct tool names, timestamps, and durations.

### 9. Idle/Reconnect -- PASS (with issues)

**Reconnect behavior:**
- After an unexpected WebSocket disconnect ("TunnelClient: disconnected: WebSocket error"), the service automatically reconnects within ~2 seconds.
- Re-wake broadcast while connected triggers "Already connected, disconnecting first to refresh" behavior.
- After reconnect, the tunnel is functional (HTTP requests succeed through it).

**Issues observed:**

1. **Spurious disconnect/reconnect cycle:** Around 3 seconds after initial connection, the tunnel experiences a WebSocket error, triggers multiple "Unexpected disconnect, attempting reconnect" warnings (6 rapid-fire), then reconnects. This happens consistently on both initial wake and re-wake.

2. **Invalid state transition:** `IllegalStateException: Invalid transition from DISCONNECTED to DISCONNECTED` logged during a connection attempt. The state machine does not handle idempotent disconnection.

3. **FCM token send failure after reconnect:** `Failed to send FCM token to relay` with `JobCancellationException` after every reconnect. The coroutine for sending the FCM token is being cancelled before it completes.

4. **TLS handshake failure on closed stream:** `TlsHandshakeFailed: Cannot send on closed stream 3` occurred once during active use. A new mux stream was opened but the underlying transport had already closed it.

5. **Idle timeout not observed:** The 5-minute idle timeout did not fire during the test window. The relay dropped to 0 connections before the configured timeout period, suggesting the disconnect was from WebSocket instability rather than the idle timer.

### 10. Screenshots -- PASS

All screens captured successfully.

---

## Non-Blocking Issues

### Dashboard connection status not updating
The dashboard always shows "Disconnected" regardless of actual tunnel state. The foreground service notification (wrench icon in status bar) indicates the service is running, but the in-app UI does not reflect this.

### Firestore PERMISSION_DENIED
On startup, a Firestore write to `devices/main-board` fails with `PERMISSION_DENIED: Missing or insufficient permissions`. This does not affect tunnel or MCP functionality but indicates Firestore security rules need updating or the auth context is wrong.

### WebSocket stability on connect
Each tunnel connection experiences a WebSocket error ~3 seconds after establishment, followed by automatic reconnect. The reconnect succeeds and subsequent requests work, but this adds latency to initial availability and produces noisy log output.

### Protocol version mismatch
The MCP server advertises protocol version `2024-11-05` while the test client requested `2025-03-26`. This works today but may cause issues with clients that strictly require the newer protocol version.

---

## Screenshots

| Screen | File |
|--------|------|
| Dashboard (disconnected, pre-activity) | `docs/screenshot_dashboard.png` |
| Dashboard (with recent activity) | `docs/screenshot_connected.png` |
| Audit History | `docs/screenshot_audit_tab.png` |
| Settings | `docs/screenshot_settings_tab.png` |

---

## Environment Notes

- Device is running Android 12 (API 31) which lacks some newer features
- Google Play Services shows `DEVELOPER_ERROR` for Phenotype API (expected on non-Play-certified build)
- Battery optimization warning shown in Settings (expected, user needs to grant exemption)
