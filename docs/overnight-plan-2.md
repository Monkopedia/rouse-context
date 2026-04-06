# Overnight Execution Plan — 2026-04-06 (Session 2)

## Priority Order

### Phase 1: Fix What's Broken
These must be done first — existing features don't work correctly.

**1a. Audit system fix** — Investigate why tool calls don't appear in audit history. Trace from McpRouting → AuditListener → RoomAuditListener → AuditDao. Fix wiring.

**1b. Phone-side OAuth approval** — Wire AuthorizationCodeManager to the existing DeviceCodeApprovalScreen/ViewModel pattern. Show pending auth requests in the app UI with approve/deny. Add a notification when a new auth request arrives so the user doesn't need to have the app open.

**1c. Token endpoint reliability** — The receiveText() hang affects /register, /token, and /mcp POST. Apply the same netIn position check fix to TlsInputStream consistently. Add integration test that sends HTTP requests with split headers/body.

**1d. Idle timeout fix** — IdleTimeoutManager should track time since last stream closed, not time since connect. If a stream is active, the timer should be paused. Reset on every stream open/close.

**1e. FCM token persistence** — When device connects via mTLS WebSocket, extract subdomain from cert CN and update InMemoryFirestore with the real FCM token. This is a stopgap until real Firestore is implemented.

### Phase 2: UI/Visual Polish
Run screenshot-based review after each change.

**2a. Purple → Navy color scheme** — Replace all purple/Material Purple references with navy (#0a1628 and lighter variants for surfaces). Test in both light and dark mode.

**2b. Light mode review** — Render screenshots in light mode, identify issues, fix colors/contrast.

**2c. Settings block backgrounds** — On dark mode, reduce opacity of gray section backgrounds so the underlying theme color comes through.

**2d. Padding audit** — Review all screens for spacing inconsistencies. Especially dashboard header area. Apply consistent 16dp horizontal, 8dp/12dp vertical padding.

**2e. Empty states** — Add placeholder text/illustrations for: empty recent activity, empty authorized clients, empty audit history.

**2f. Authorized clients: show names** — Store client_name from dynamic registration alongside client_id. Display name in the authorized clients list instead of UUID.

**2g. Hide connection state in release** — Wrap the connection status indicator in `if (BuildConfig.DEBUG)`.

**2h. Remove/clarify global notification setting** — Investigate what it controls. Either remove or add clear description.

**2i. Add Client flow** — Remove URL from top of dashboard. Add "Add Client" button at bottom of authorized clients section that shows the connection URL + instructions.

### Phase 2.5: Connection & Protocol Hardening

**2.5a. Auto-reconnect with backoff** — When WebSocket drops, TunnelForegroundService should retry with exponential backoff (1s, 2s, 4s, max 30s) instead of stopping. Only stop on explicit disconnect or user action.

**2.5b. WebSocket keepalive** — Configure OkHttp's `pingInterval(15, SECONDS)` on the mTLS WebSocket client. Relay-side: detect stale connections (no frames in 60s) and clean up session registry.

**2.5c. MuxDemux thread safety** — Replace `mutableMapOf` with `ConcurrentHashMap` for the streams map. Add stream leak protection (force-close after 10 minutes).

**2.5d. Bridge timeout** — Add 30s timeout on bridge copy operations. Graceful cleanup: always send CLOSE frame and close Ktor socket on failure.

**2.5e. Auth cleanup** — Periodic cleanup of expired AuthorizationCodeManager/DeviceCodeManager entries. Rate limiting on /token, /register, /authorize.

**2.5f. Error handling** — Replace silent exception swallowing with structured logging. MCP tool exceptions return proper JSON-RPC error responses.

**2.5g. Integration test refactor** — Refactor EndToEndSessionTest to use TunnelClientImpl + core:bridge SessionHandler instead of manually pumping frames. Extract shared test utilities from duplicated code (~600 lines). Add test for TLS with split records, authorization code PKCE flow, session lifecycle.

### Phase 3: Integration Enable/Disable & Onboarding
**3a. Fix integration enable/disable flow** — Add Integration button, Health Connect setup flow, permission request.

**3b. Non-blocking onboarding** — Dashboard shows immediately. Banner at top if not onboarded. Onboarding runs in background or on tap.

**3c. Gate certs on integrations** — Don't request ACME cert during onboarding if no integration is enabled. Request when first integration is enabled.

### Phase 4: New Integrations
Each integration needs: module, MCP provider, setup UI, permission handling, tests.

**4a. Notifications integration** — See docs/design/notifications-integration.md

**4b. Outreach integration** — See docs/design/outreach-integration.md (with optional DND)

**4c. Usage integration** — See docs/design/usage-integration.md

**4d. Health Connect expansion** — See docs/design/health-connect-expansion.md

### Phase 5: Infrastructure
**5a. Real Firestore client** — Replace InMemoryFirestore on the relay.

**5b. Lower minSdk** — Investigate what breaks below API 28, add feature flags.

### Phase 6: Device Integration Testing
Run after all implementation is complete. Uses the Pixel 3 XL attached to adolin.lan via ADB (`~/Android/Sdk/platform-tools/adb -s 84TY004PW`).

**6a. Build, deploy, and smoke test** — `./gradlew :app:assembleDebug`, scp to adolin, adb install. Launch app, verify it starts without crash.

**6b. Onboarding flow** — If not already onboarded, walk through: Firebase auth → relay registration → cert issuance → subdomain assignment. Verify dashboard shows after.

**6c. Tunnel connectivity** — Wake via ADB broadcast, verify relay shows `active_mux_connections: 1`. Test HTTP through tunnel with curl + `--resolve`.

**6d. OAuth flow** — Hit the MCP endpoint, verify 401 → discovery → register → authorize page → approve via ADB → token exchange → 200. Verify tools/list returns tools.

**6e. Tool calls** — Call each available tool (echo, get_time, device_info) via curl through the tunnel. Verify correct responses.

**6f. Integration setup** — Enable Health Connect (if available on device). Verify it appears in the integration list. Test health tools if data available.

**6g. Audit verification** — After tool calls, check audit history in the app (via screenshot or adb activity launch). Verify entries appear.

**6h. Notification/approval UX** — Trigger an auth request, verify notification appears on device. Tap approve, verify flow completes.

**6i. UI screenshots** — Capture screenshots of all screens in both light and dark mode. Review for visual issues. Use `adb shell screencap` + `adb pull`.

**6j. Idle/reconnect** — Wait for idle timeout, verify service stops gracefully. Re-wake, verify reconnect works. Test double-wake handling.

**6k. Write test report** — Document what passed, what failed, blockers found. Save to `docs/device-test-report.md`.

### Phase 7: Security Audit (LAST)
**7. Comprehensive security review** — All permissions, data flows, auth, cert handling, relay security, OWASP review of HTTP endpoints, audit of all special permissions.

## Agent Strategy
- Use opus for all coding work
- 3-4 agents concurrent max
- Don't edit .claude/ files
- Each agent works in a worktree
- Commit with clear messages
- Run tests + ktlintFormat before committing
- If blocked, document in BLOCKERS.md and skip

## Build Commands
- Kotlin: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :module:task`
- Relay: `cd relay && cargo test && cargo clippy`
- Format: `./gradlew ktlintFormat`
