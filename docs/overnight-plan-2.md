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

### Phase 6: Security Audit (LAST)
**6. Comprehensive security review** — All permissions, data flows, auth, cert handling, relay security, OWASP review of HTTP endpoints, audit of all special permissions.

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
