# Workflow & Dependency Tree

This document defines the implementation roadmap for Rouse Context. It is designed so that multiple agents can work in parallel on isolated git worktrees, each completing an independent task with TDD.

## Current State

The existing codebase has four modules (`:app`, `:tunnel`, `:mcp-core`, `:mcp-health`) configured as Android-only Gradle modules. All contain stub implementations. The relay does not exist yet.

The target architecture restructures to: `:core:tunnel`, `:core:mcp`, `:api`, `:health`, `:notifications`, `:work`, `:app` on the Kotlin/Android side, plus `relay/` as an independent Rust project.

## Module Dependency Graph

```
:core:tunnel         (KMP: jvm + android)  ← no project module deps
:core:mcp            (KMP: jvm + android)  ← no project module deps
:api                 (Android library)     ← :core:mcp
:health              (Android library)     ← :api, :core:mcp
:notifications       (Android library)     ← :core:tunnel, :core:mcp
:work                (Android library)     ← :core:tunnel, :notifications
:app                 (Android application) ← :core:tunnel, :core:mcp, :api, :health, :notifications, :work

relay/               (Rust, Cargo)         ← completely independent
```

### Independence Analysis

**Fully independent (zero inter-module deps, can be built in total isolation):**
- `:core:tunnel` — mux protocol, framing, WebSocket client, state machine, CertificateStore interface
- `:core:mcp` — McpSession, ProviderRegistry, TokenStore, OAuth device code flow, HTTP routing
- `relay/` — Rust binary, no Kotlin deps at all

**Depends only on leaf modules (can start once leaf interfaces are defined):**
- `:api` — depends on `:core:mcp` for `McpServerProvider` interface only
- `:notifications` — depends on `:core:tunnel` for `TunnelState`/`TunnelError` types, `:core:mcp` for `AuditListener`
- `:work` — depends on `:core:tunnel` for `TunnelClient` interface, `:notifications` for `createForegroundNotification()`

**Depends on everything (integration layer, must come last):**
- `:app` — Koin wiring, Compose UI, navigation, ties all modules together
- `:health` — depends on `:api` and `:core:mcp`, but is a leaf feature module

## Implementation Phases

### Phase 0: Scaffold (sequential, one agent)

Restructure the Gradle project from the current flat module layout to the target nested layout. This must be done first because every subsequent task assumes the new module structure.

| Task ID | Task | Description |
|---------|------|-------------|
| S-1 | Gradle restructure | Rename modules, set up KMP for `:core:tunnel` and `:core:mcp`, create empty `:api`, `:notifications`, `:work` modules, update `settings.gradle.kts`, update `libs.versions.toml` with KMP plugin + new deps, migrate existing source files |

**Acceptance criteria:** `./gradlew assemble` succeeds. Existing `McpSessionTest` passes via `./gradlew :core:mcp:jvmTest`. All modules resolve their dependencies. ktlint and detekt pass.

**Files to create/modify:**
- `settings.gradle.kts` — replace flat includes with nested (`core:tunnel`, `core:mcp`, etc.)
- `build.gradle.kts` (root) — add KMP plugin alias
- `core/tunnel/build.gradle.kts` — KMP with jvm + android targets
- `core/mcp/build.gradle.kts` — KMP with jvm + android targets
- `api/build.gradle.kts` — Android library
- `health/build.gradle.kts` — Android library (was `mcp-health`)
- `notifications/build.gradle.kts` — Android library
- `work/build.gradle.kts` — Android library
- `app/build.gradle.kts` — update deps to new module paths
- `gradle/libs.versions.toml` — add KMP plugin, Ktor server, Room, Koin, Compose, WorkManager, kotlinx-datetime
- Move `mcp-core/src/` to `core/mcp/src/commonMain/` (and test to `jvmTest`)
- Move `tunnel/src/` to `core/tunnel/src/androidMain/` (stub) and create `commonMain`
- Move `mcp-health/src/` to `health/src/`
- Update package names if needed (keep `com.rousecontext.*`)

**Branch:** `scaffold/gradle-restructure`

---

### Phase 1: Leaf Modules (fully parallel — 4 agents)

These tasks have zero inter-module dependencies. Each agent works in an isolated worktree on its own branch.

#### Task T-1: core:tunnel — Mux Framing & Protocol

**Owner:** tunnel agent
**Branch:** `feat/tunnel-mux-framing`
**Depends on:** S-1

Implement the mux binary framing protocol, stream demux, and connection state machine in `commonMain`. No Android, no network — pure byte-level protocol logic.

**Tests first (in `commonTest` and `jvmTest`):**
- `MuxFrameTest` — encode/decode each frame type (DATA, OPEN, CLOSE, ERROR), edge cases (unknown type, truncated frame, max payload)
- `MuxDemuxTest` — OPEN creates stream, DATA routes to correct stream, CLOSE tears down, ERROR propagates, unknown stream_id ignored
- `ConnectionStateMachineTest` — all state transitions: DISCONNECTED->CONNECTING->CONNECTED->ACTIVE->DISCONNECTING->DISCONNECTED, invalid transitions rejected
- `MuxStreamTest` — write to output produces DATA frame, close sends CLOSE frame, concurrent writes interleave correctly

**Files to create:**
- `core/tunnel/src/commonMain/kotlin/com/rousecontext/tunnel/MuxFrame.kt` — frame types, encode/decode
- `core/tunnel/src/commonMain/kotlin/com/rousecontext/tunnel/MuxDemux.kt` — stream multiplexer/demultiplexer
- `core/tunnel/src/commonMain/kotlin/com/rousecontext/tunnel/MuxStream.kt` — per-stream I/O wrapper
- `core/tunnel/src/commonMain/kotlin/com/rousecontext/tunnel/TunnelState.kt` — state enum
- `core/tunnel/src/commonMain/kotlin/com/rousecontext/tunnel/TunnelError.kt` — error sealed interface
- `core/tunnel/src/commonMain/kotlin/com/rousecontext/tunnel/CertificateStore.kt` — interface definition
- `core/tunnel/src/commonMain/kotlin/com/rousecontext/tunnel/TunnelClient.kt` — interface definition
- `core/tunnel/src/commonTest/kotlin/com/rousecontext/tunnel/MuxFrameTest.kt`
- `core/tunnel/src/commonTest/kotlin/com/rousecontext/tunnel/MuxDemuxTest.kt`
- `core/tunnel/src/jvmTest/kotlin/com/rousecontext/tunnel/ConnectionStateMachineTest.kt`
- `core/tunnel/src/jvmTest/kotlin/com/rousecontext/tunnel/MuxStreamTest.kt`

**Acceptance criteria:**
- All frame types encode/decode correctly per relay.md spec (5-byte header: type u8, stream_id u32 BE, payload)
- State machine matches the spec: DISCONNECTED, CONNECTING, CONNECTED, ACTIVE, DISCONNECTING
- `TunnelClient` interface matches the design doc (state, errors, connect, disconnect, incomingSessions)
- `CertificateStore` interface matches the design doc
- All tests pass via `./gradlew :core:tunnel:jvmTest`

---

#### Task T-2: core:mcp — HTTP Server, OAuth, Token Management

**Owner:** mcp agent
**Branch:** `feat/mcp-http-oauth`
**Depends on:** S-1

Rebuild McpSession around Ktor embedded HTTP server with path-based routing, per-integration OAuth device code flow, and token management. This replaces the current StdioServerTransport approach with Streamable HTTP.

**Tests first (in `jvmTest`):**
- `HttpRoutingTest` — `/{integration}/.well-known/oauth-authorization-server` returns valid RFC 8414 metadata; unknown path returns 404; disabled integration returns 404
- `DeviceCodeFlowTest` — POST `/health/device/authorize` returns device_code + user_code; POST `/health/token` before approval returns `authorization_pending`; after approval returns access_token; after denial returns `access_denied`; after 10min returns `expired_token`; multiple concurrent codes tracked independently
- `TokenStoreTest` — create, validate, revoke, list, hasTokens per integration; cross-integration token rejected
- `AuthMiddlewareTest` — request without Bearer returns 401 with correct WWW-Authenticate; valid token passes through; revoked token returns 401; wrong-integration token returns 401; OAuth endpoints accessible without Bearer
- `ProviderRegistryTest` — enabled integration found; disabled returns null; enable/disable reflected immediately; `enabledPaths()` accurate
- `McpSessionTest` (update existing) — full round-trip: HTTP POST to `/health` with valid Bearer returns MCP initialize response; `tools/list`; `tools/call`; `resources/list`; `resources/read`

**Files to create/modify:**
- `core/mcp/src/commonMain/kotlin/com/rousecontext/mcp/core/McpSession.kt` — rewrite: Ktor embedded HTTP over InputStream/OutputStream, path routing
- `core/mcp/src/commonMain/kotlin/com/rousecontext/mcp/core/McpServerProvider.kt` — keep interface, possibly adjust
- `core/mcp/src/commonMain/kotlin/com/rousecontext/mcp/core/ProviderRegistry.kt` — interface
- `core/mcp/src/commonMain/kotlin/com/rousecontext/mcp/core/TokenStore.kt` — interface
- `core/mcp/src/commonMain/kotlin/com/rousecontext/mcp/core/AuditListener.kt` — keep, add sessionId + providerId fields to ToolCallEvent
- `core/mcp/src/commonMain/kotlin/com/rousecontext/mcp/core/DeviceCodeManager.kt` — device code state, generation, approval, expiry
- `core/mcp/src/commonMain/kotlin/com/rousecontext/mcp/core/OAuthMetadata.kt` — RFC 8414 metadata generation
- `core/mcp/src/commonMain/kotlin/com/rousecontext/mcp/core/AuthMiddleware.kt` — Bearer token verification per integration
- `core/mcp/src/jvmTest/kotlin/com/rousecontext/mcp/core/HttpRoutingTest.kt`
- `core/mcp/src/jvmTest/kotlin/com/rousecontext/mcp/core/DeviceCodeFlowTest.kt`
- `core/mcp/src/jvmTest/kotlin/com/rousecontext/mcp/core/TokenStoreTest.kt`
- `core/mcp/src/jvmTest/kotlin/com/rousecontext/mcp/core/AuthMiddlewareTest.kt`
- `core/mcp/src/jvmTest/kotlin/com/rousecontext/mcp/core/ProviderRegistryTest.kt`
- `core/mcp/src/jvmTest/kotlin/com/rousecontext/mcp/core/McpSessionTest.kt` — rewrite for HTTP

**Acceptance criteria:**
- McpSession runs a Ktor HTTP server over raw InputStream/OutputStream
- Each integration path is a self-contained MCP server with its own OAuth
- Device code flow implements RFC 8628 (device_code, user_code, polling, expiry)
- Token validation is per-integration (token for `/health` cannot access `/notifications`)
- All tests pass via `./gradlew :core:mcp:jvmTest`

---

#### Task T-3: Relay — Rust Project Scaffold + Config + SNI Router

**Owner:** relay agent
**Branch:** `feat/relay-scaffold`
**Depends on:** S-1 (loosely — only needs the repo, not Kotlin modules)

Create the Rust project from scratch. Config parsing, SNI routing, TLS termination for relay's own cert, and the mux framing protocol.

**Tests first:**
- `config_test.rs` — parse valid TOML; missing file uses defaults; env var overrides; missing required env var fails with clear message
- `sni_test.rs` — extract SNI from real TLS ClientHello bytes; relay hostname routes to API; device subdomain routes to passthrough; unknown SNI closes connection
- `mux_frame_test.rs` — encode/decode all frame types; round-trip; unknown type ignored; truncated frame errors
- `subdomain_test.rs` — generated subdomain matches format (adjective-noun, lowercase, DNS-safe); no collision in 1000 generations against empty set

**Files to create:**
- `relay/Cargo.toml`
- `relay/src/main.rs` — entry point, tokio runtime, config load, bind port
- `relay/src/config.rs` — TOML parsing, env var overrides, defaults
- `relay/src/sni.rs` — TLS ClientHello parser, SNI extraction
- `relay/src/router.rs` — SNI-based routing (relay API vs device passthrough)
- `relay/src/tls.rs` — rustls config: server cert, optional client cert verification
- `relay/src/mux/mod.rs` — mux module
- `relay/src/mux/frame.rs` — frame encode/decode
- `relay/src/mux/connection.rs` — per-device mux connection state
- `relay/src/subdomain.rs` — word lists, generation, format validation
- `relay/src/words/adjectives.txt` — ~2000 adjectives
- `relay/src/words/nouns.txt` — ~2000 nouns
- `relay/tests/config_test.rs`
- `relay/tests/sni_test.rs`
- `relay/tests/mux_frame_test.rs`
- `relay/tests/subdomain_test.rs`

**Acceptance criteria:**
- `cargo test` passes all tests
- `cargo build --release` produces a binary
- Config loads from `relay.toml` with env var overrides
- SNI parser correctly extracts hostname from real TLS ClientHello
- Mux framing matches the protocol spec exactly (interoperable with Kotlin client)
- Subdomain generation produces valid DNS-safe two-word names

---

#### Task T-4: Relay — API Endpoints + FCM + Firestore

**Owner:** relay agent (or second relay agent)
**Branch:** `feat/relay-api`
**Depends on:** T-3

Implement the HTTP API endpoints (`/register`, `/renew`, `/wake/:subdomain`, `/ws`, `/status`), FCM integration, and Firestore client.

**Tests first:**
- `api_register_test.rs` — happy path: valid Firebase token + CSR returns subdomain + cert; re-registration with signature reuses subdomain; `force_new` assigns new subdomain; rotation within 30 days rejected
- `api_renew_test.rs` — mTLS path: valid cert extracts subdomain, returns new cert; Firebase path: valid token + signature returns new cert; invalid signature rejected; wrong UID rejected
- `api_wake_test.rs` — online device returns 200 immediately; offline device fires FCM, returns 200 on connect or 504 on timeout; rate limit (>6/min) returns 429
- `api_status_test.rs` — returns correct counts for connections, streams, uptime
- `fcm_test.rs` — build correct FCM payload; OAuth2 token cached; token refresh on expiry
- `firestore_test.rs` — read/write device record; read/write pending_certs; cache with TTL

**Files to create:**
- `relay/src/api/mod.rs`
- `relay/src/api/register.rs`
- `relay/src/api/renew.rs`
- `relay/src/api/wake.rs`
- `relay/src/api/status.rs`
- `relay/src/api/ws.rs` — WebSocket upgrade for mux
- `relay/src/fcm.rs` — FCM REST client
- `relay/src/firestore.rs` — Firestore REST client
- `relay/src/acme.rs` — ACME client (DNS-01 via Cloudflare)
- `relay/src/firebase_auth.rs` — Firebase ID token verification
- `relay/src/rate_limit.rs` — per-subdomain token bucket for `/wake`
- `relay/src/state.rs` — in-memory state: active mux connections, streams, device cache
- `relay/tests/api_register_test.rs`
- `relay/tests/api_renew_test.rs`
- `relay/tests/api_wake_test.rs`
- `relay/tests/api_status_test.rs`

**Acceptance criteria:**
- All API endpoints match the spec in relay.md
- Firebase token verification works
- FCM sends data-only messages with correct format
- Firestore reads/writes device and pending_certs collections
- Rate limiting on `/wake` enforced correctly
- All tests pass via `cargo test`

---

### Phase 2: Integration Modules (parallel — 3 agents)

These depend on Phase 1 leaf interfaces being defined (not fully implemented — just the interface types from T-1 and T-2 need to exist).

#### Task T-5: core:tunnel — WebSocket Client + TLS Accept

**Owner:** tunnel agent
**Branch:** `feat/tunnel-websocket-tls`
**Depends on:** T-1

Connect the mux framing to real Ktor WebSocket transport and TLS server-side accept. This makes TunnelClient a real implementation.

**Tests first (in `jvmTest`):**
- `WebSocketMuxTest` — connect to in-process test WebSocket server, send/receive mux frames over real WebSocket binary messages
- `TlsAcceptTest` — OPEN frame triggers TLS server accept using test cert; client TLS handshake completes; plaintext flows through MuxStream; invalid TLS from client handled gracefully
- `TunnelClientImplTest` — connect() transitions DISCONNECTED->CONNECTING->CONNECTED; OPEN received emits to incomingSessions; disconnect() sends CLOSE for all streams; WebSocket drop transitions to DISCONNECTED; errors emitted on SharedFlow

**Files to create:**
- `core/tunnel/src/commonMain/kotlin/com/rousecontext/tunnel/TunnelClientImpl.kt` — WebSocket connection, mux dispatch, TLS accept
- `core/tunnel/src/commonMain/kotlin/com/rousecontext/tunnel/TlsAcceptor.kt` — TLS server-side handshake using CertificateStore
- `core/tunnel/src/jvmTest/kotlin/com/rousecontext/tunnel/WebSocketMuxTest.kt`
- `core/tunnel/src/jvmTest/kotlin/com/rousecontext/tunnel/TlsAcceptTest.kt`
- `core/tunnel/src/jvmTest/kotlin/com/rousecontext/tunnel/TunnelClientImplTest.kt`
- `core/tunnel/src/jvmTest/kotlin/com/rousecontext/tunnel/TestCertificateStore.kt` — in-memory test impl

**Acceptance criteria:**
- TunnelClientImpl connects via Ktor WebSocket, authenticates with mTLS
- Incoming OPEN frames create TLS-wrapped MuxStreams emitted via `incomingSessions`
- State transitions match the design doc state machine
- All tests pass via `./gradlew :core:tunnel:jvmTest`

---

#### Task T-6: api — Provider Contract + IntegrationStateStore

**Owner:** android agent
**Branch:** `feat/api-contract`
**Depends on:** T-2 (needs McpServerProvider interface)

Define the `McpIntegration` interface and `IntegrationStateStore`. This is the contract that all integration modules (`:health`, future modules) implement.

**Tests first:**
- `IntegrationStateStoreTest` — enable/disable persists; observe emits changes; default is disabled
- `IntegrationStateTest` — derive Available/Disabled/Pending/Active/Unavailable from userEnabled + hasTokens + isAvailable

**Files to create:**
- `api/src/main/kotlin/com/rousecontext/api/McpIntegration.kt` — full interface with Nav3 registration
- `api/src/main/kotlin/com/rousecontext/api/IntegrationStateStore.kt` — interface
- `api/src/main/kotlin/com/rousecontext/api/IntegrationState.kt` — enum: Available, Disabled, Pending, Active, Unavailable
- `api/src/main/kotlin/com/rousecontext/api/NotificationSettingsProvider.kt` — interface
- `api/src/test/kotlin/com/rousecontext/api/IntegrationStateStoreTest.kt`
- `api/src/test/kotlin/com/rousecontext/api/IntegrationStateTest.kt`

**Acceptance criteria:**
- `McpIntegration` interface matches android-app.md exactly
- Integration state derivation logic correct for all 5 states
- All tests pass via `./gradlew :api:testDebugUnitTest`

---

#### Task T-7: notifications — NotificationModel + Audit Persistence

**Owner:** android agent
**Branch:** `feat/notifications-model`
**Depends on:** T-1 (needs TunnelState/TunnelError types), T-2 (needs AuditListener)

Implement the pure-function NotificationModel state machine and Room-based audit persistence.

**Tests first:**
- `NotificationModelTest` — all 15 notification scenarios from the design doc (test IDs 167-181 in overall.md): MuxConnected->ShowForeground; MuxDisconnected with tool calls + Summary->PostSummary; MuxDisconnected with zero calls->PostWarning; StreamOpened->foreground updates; ErrorOccurred connection-level->PostError; permission denied->forced suppress; etc.
- `AuditDaoTest` — insert, query by sessionId, query by date range + provider, retention pruning (>30 days deleted), empty state

**Files to create:**
- `notifications/src/main/kotlin/com/rousecontext/notifications/NotificationModel.kt` — pure state machine
- `notifications/src/main/kotlin/com/rousecontext/notifications/SessionEvent.kt` — sealed interface
- `notifications/src/main/kotlin/com/rousecontext/notifications/NotificationAction.kt` — sealed interface
- `notifications/src/main/kotlin/com/rousecontext/notifications/NotificationSettings.kt` — data class
- `notifications/src/main/kotlin/com/rousecontext/notifications/NotificationAdapter.kt` — maps NotificationAction to Android NotificationManager
- `notifications/src/main/kotlin/com/rousecontext/notifications/ForegroundNotification.kt` — `createForegroundNotification()`
- `notifications/src/main/kotlin/com/rousecontext/notifications/NotificationChannels.kt` — channel setup
- `notifications/src/main/kotlin/com/rousecontext/notifications/audit/AuditDatabase.kt` — Room database
- `notifications/src/main/kotlin/com/rousecontext/notifications/audit/AuditDao.kt` — Room DAO
- `notifications/src/main/kotlin/com/rousecontext/notifications/audit/AuditEntry.kt` — Room entity
- `notifications/src/main/kotlin/com/rousecontext/notifications/audit/RoomAuditListener.kt` — implements AuditListener
- `notifications/src/test/kotlin/com/rousecontext/notifications/NotificationModelTest.kt`
- `notifications/src/androidTest/kotlin/com/rousecontext/notifications/audit/AuditDaoTest.kt`

**Acceptance criteria:**
- NotificationModel is a pure function: `onEvent(event) -> List<NotificationAction>`. No Android dependencies in the model itself.
- All 15 notification scenarios from the design doc pass
- Audit retention pruning deletes entries older than 30 days
- All tests pass

---

### Phase 3: Service Layer + Security (parallel — 3-4 agents)

#### Task T-8: work — Foreground Service, FCM Receiver, WorkManager, Wakelock

**Owner:** android agent
**Branch:** `feat/work-service`
**Depends on:** T-5 (needs TunnelClientImpl), T-7 (needs createForegroundNotification)

The Android service lifecycle layer. Bridges FCM wakeups to TunnelClient, manages wakelocks, schedules cert renewal.

**Tests first:**
- `FcmDispatchTest` — `type: "wake"` starts service; `type: "renew"` enqueues WorkManager; unknown type logged and ignored
- `WakelockManagerTest` — ACTIVE acquires; CONNECTED releases; CONNECTING acquires; DISCONNECTED releases; rapid transitions balanced (no leak)
- `IdleTimeoutTest` — CONNECTED for N minutes triggers disconnect; stream arrival cancels timer; disabled when battery exempt
- `CertRenewalWorkerTest` — <14 days triggers renewal; not expiring is no-op; rate_limited schedules retry

**Files to create:**
- `work/src/main/kotlin/com/rousecontext/work/TunnelForegroundService.kt`
- `work/src/main/kotlin/com/rousecontext/work/FcmReceiver.kt` — FirebaseMessagingService subclass
- `work/src/main/kotlin/com/rousecontext/work/WakelockManager.kt`
- `work/src/main/kotlin/com/rousecontext/work/IdleTimeoutManager.kt`
- `work/src/main/kotlin/com/rousecontext/work/CertRenewalWorker.kt`
- `work/src/test/kotlin/com/rousecontext/work/WakelockManagerTest.kt`
- `work/src/test/kotlin/com/rousecontext/work/IdleTimeoutTest.kt`
- `work/src/test/kotlin/com/rousecontext/work/CertRenewalWorkerTest.kt`

**Acceptance criteria:**
- FCM receiver dispatches correctly by type
- Wakelock is always balanced (acquired and released in matching pairs)
- Idle timeout is cancellable and respects battery exemption
- CertRenewalWorker checks expiry and triggers renewal with correct auth path
- All tests pass

---

#### Task T-9: core:tunnel — Onboarding + Cert Renewal Execution

**Owner:** tunnel agent
**Branch:** `feat/tunnel-onboarding`
**Depends on:** T-5

Implement the onboarding flow (keypair, CSR, /register) and cert renewal (/renew with mTLS or Firebase+signature) in the tunnel module.

**Tests first (in `jvmTest`):**
- `OnboardingFlowTest` — full flow with mock relay: keypair generated, CSR created, /register called, cert+subdomain stored; relay unreachable returns error; rate limited returns error with retry_after; partial failure leaves no state
- `CertRenewalTest` — valid cert: mTLS /renew succeeds; expired cert: Firebase+signature /renew succeeds; CN/SAN mismatch rejected; rate limited schedules retry; network failure retries with backoff

**Files to create:**
- `core/tunnel/src/commonMain/kotlin/com/rousecontext/tunnel/OnboardingFlow.kt` — orchestrates keypair->CSR->/register->store
- `core/tunnel/src/commonMain/kotlin/com/rousecontext/tunnel/CertRenewalFlow.kt` — orchestrates /renew with correct auth path
- `core/tunnel/src/commonMain/kotlin/com/rousecontext/tunnel/CsrGenerator.kt` — expect/actual for CSR creation
- `core/tunnel/src/commonMain/kotlin/com/rousecontext/tunnel/RelayApiClient.kt` — Ktor HTTP client for /register, /renew
- `core/tunnel/src/androidMain/kotlin/com/rousecontext/tunnel/AndroidCsrGenerator.kt` — Bouncy Castle CSR with Keystore signing
- `core/tunnel/src/jvmMain/kotlin/com/rousecontext/tunnel/JvmCsrGenerator.kt` — test implementation
- `core/tunnel/src/jvmTest/kotlin/com/rousecontext/tunnel/OnboardingFlowTest.kt`
- `core/tunnel/src/jvmTest/kotlin/com/rousecontext/tunnel/CertRenewalTest.kt`
- `core/tunnel/src/jvmTest/kotlin/com/rousecontext/tunnel/MockRelayServer.kt` — in-process mock HTTP server

**Acceptance criteria:**
- Onboarding produces no partial state on failure
- Cert renewal chooses correct auth path based on cert validity
- Post-renewal validation rejects CN/SAN mismatches
- All tests pass via `./gradlew :core:tunnel:jvmTest`

---

#### Task T-10: Relay — Client Passthrough + Mux Lifecycle + Graceful Shutdown

**Owner:** relay agent
**Branch:** `feat/relay-passthrough`
**Depends on:** T-4

Wire the SNI router to actual client TCP passthrough and mux WebSocket lifecycle. Implement graceful shutdown.

**Tests first:**
- `passthrough_test.rs` — client TCP with device subdomain SNI: buffer ClientHello, OPEN to mux, DATA bidirectional, CLOSE teardown
- `mux_lifecycle_test.rs` — WebSocket connect with valid cert -> mux active; OPEN/DATA/CLOSE full cycle; max streams exceeded -> ERROR(STREAM_REFUSED); WebSocket drop -> all client connections closed
- `shutdown_test.rs` — SIGTERM: no new connections accepted; CLOSE sent for all streams; drain timeout; WebSocket closed; stats logged
- `fcm_wakeup_test.rs` — cold client: SNI lookup, no mux, FCM sent, device connects within timeout, stream created; FCM timeout -> client connection closed

**Files to create:**
- `relay/src/passthrough.rs` — client TCP to mux stream bridging
- `relay/src/mux/lifecycle.rs` — mux connection lifecycle, stream tracking
- `relay/src/shutdown.rs` — graceful shutdown handler
- `relay/src/maintenance.rs` — daily job: cert expiry nudge, pending queue, subdomain reclamation
- `relay/tests/passthrough_test.rs`
- `relay/tests/mux_lifecycle_test.rs`
- `relay/tests/shutdown_test.rs`

**Acceptance criteria:**
- Client TLS ClientHello buffered and replayed correctly (relay never consumes it)
- Mux stream lifecycle matches the protocol spec
- Max 8 concurrent streams per device enforced
- Graceful shutdown drains within 5s timeout
- Daily maintenance job processes cert nudges, pending queue, and reclamation
- All tests pass via `cargo test`

---

#### Task T-11: Security Monitoring — Self-Cert Verification + CT Log Check

**Owner:** android agent (or tunnel agent)
**Branch:** `feat/security-monitoring`
**Depends on:** T-5 (needs CertificateStore with fingerprints), T-7 (needs audit persistence)

Implement the two security monitoring mechanisms from the design doc: self-cert verification via custom X509TrustManager, and Certificate Transparency log monitoring via crt.sh API. Both run as periodic WorkManager tasks.

**Tests first:**
- `SelfCertVerifierTest` — fingerprint matches provisioned cert → verified; fingerprint mismatch → alert; during renewal window, both old and new fingerprints accepted; network error → warning (not alert); cert chain with intermediate → checks leaf only
- `CtLogMonitorTest` — crt.sh returns only app-issued cert → verified; crt.sh returns unknown cert → alert; crt.sh unreachable → warning; empty response (new subdomain, no certs yet) → verified; malformed JSON handled gracefully
- `SecurityCheckWorkerTest` — runs both checks; stores results in preferences and audit log; schedules next run; alert result triggers notification

**Files to create:**
- `core/tunnel/src/commonMain/kotlin/com/rousecontext/tunnel/SelfCertVerifier.kt` — custom X509TrustManager, SHA-256 fingerprint comparison
- `core/tunnel/src/commonMain/kotlin/com/rousecontext/tunnel/CtLogMonitor.kt` — crt.sh query, JSON parsing, fingerprint cross-reference
- `core/tunnel/src/commonMain/kotlin/com/rousecontext/tunnel/SecurityCheckResult.kt` — sealed: Verified, Warning(reason), Alert(reason)
- `work/src/main/kotlin/com/rousecontext/work/SecurityCheckWorker.kt` — periodic WorkManager task (every 4 hours)
- `core/tunnel/src/jvmTest/kotlin/com/rousecontext/tunnel/SelfCertVerifierTest.kt`
- `core/tunnel/src/jvmTest/kotlin/com/rousecontext/tunnel/CtLogMonitorTest.kt`
- `work/src/test/kotlin/com/rousecontext/work/SecurityCheckWorkerTest.kt`

**CertificateStore additions:**
```kotlin
/** SHA-256 fingerprints of cert public keys we issued (current + pending renewal) */
fun getKnownFingerprints(): Set<String>

/** Store a fingerprint for a newly issued cert */
fun storeFingerprint(fingerprint: String)
```

**Acceptance criteria:**
- Self-cert check detects fingerprint mismatch and raises alert
- CT log check detects unknown certs and raises alert
- Both checks degrade to warning on network failure (never silent)
- Results persisted to audit log and preferences
- WorkManager schedules every 4 hours with flex window
- All tests pass

---

### Phase 4: Health Integration (1 agent)

#### Task T-12: health — Health Connect MCP Server

**Owner:** android agent
**Branch:** `feat/health-integration`
**Depends on:** T-6 (needs McpIntegration interface)

Implement the Health Connect integration: MCP tools for steps, heart rate, sleep; permission handling; onboarding and settings screens.

**Tests first:**
- `HealthConnectMcpServerTest` — tools registered (get_steps, get_heart_rate, get_sleep); tool call with valid args returns data; unknown tool returns error; Health Connect unavailable returns error
- `HealthConnectIntegrationTest` — isAvailable checks SDK; onboardingRoute = "setup"; settingsRoute = "settings"; id/path/displayName correct

**Files to create/modify:**
- `health/src/main/kotlin/com/rousecontext/health/HealthConnectIntegration.kt` — implements McpIntegration
- `health/src/main/kotlin/com/rousecontext/health/HealthConnectMcpServer.kt` — rewrite with actual tools
- `health/src/main/kotlin/com/rousecontext/health/HealthConnectRepository.kt` — wraps Health Connect SDK
- `health/src/main/kotlin/com/rousecontext/health/ui/SetupScreen.kt` — Compose onboarding
- `health/src/main/kotlin/com/rousecontext/health/ui/SettingsScreen.kt` — Compose settings
- `health/src/test/kotlin/com/rousecontext/health/HealthConnectMcpServerTest.kt`
- `health/src/test/kotlin/com/rousecontext/health/HealthConnectIntegrationTest.kt`

**Acceptance criteria:**
- MCP tools for steps, heart rate, and sleep data registered and callable
- Permission handling with explanation screens
- Integration implements McpIntegration fully
- All tests pass

---

### Phase 5: App Shell (1 agent, but can split UI tasks)

#### Task T-13: app — Koin Wiring + CertificateStore Implementation

**Owner:** android agent
**Branch:** `feat/app-koin-wiring`
**Depends on:** T-5, T-6, T-7, T-8

Wire all modules together via Koin. Implement concrete CertificateStore (PEM files + Keystore), RoomTokenStore, DataStore-backed IntegrationStateStore.

**Files to create:**
- `app/src/main/kotlin/com/rousecontext/app/di/AppModule.kt` — Koin module as specified in android-app.md
- `app/src/main/kotlin/com/rousecontext/app/cert/FileCertificateStore.kt`
- `app/src/main/kotlin/com/rousecontext/app/token/RoomTokenStore.kt`
- `app/src/main/kotlin/com/rousecontext/app/token/TokenDatabase.kt`
- `app/src/main/kotlin/com/rousecontext/app/state/DataStoreIntegrationStateStore.kt`
- `app/src/main/kotlin/com/rousecontext/app/state/DataStoreNotificationSettingsProvider.kt`
- `app/src/main/kotlin/com/rousecontext/app/registry/IntegrationProviderRegistry.kt` — implements ProviderRegistry using McpIntegration list + IntegrationStateStore

**Acceptance criteria:**
- Koin graph resolves without cycles
- CertificateStore reads/writes PEM, accesses Keystore private key
- TokenStore persists tokens in Room, scoped per integration
- IntegrationStateStore backed by Preferences DataStore

---

#### Task T-14: app — Compose Navigation + All Screens

**Owner:** android agent (can be split into sub-tasks per screen)
**Branch:** `feat/app-ui`
**Depends on:** T-13

Build the Compose UI shell: all app-owned screens, navigation, ViewModels, bottom nav.

**Sub-tasks (can be further split into per-screen branches):**

| Sub-task | Screen | ViewModel |
|----------|--------|-----------|
| T-14a | Welcome + onboarding flow | - |
| T-14b | Main Dashboard | MainDashboardViewModel |
| T-14c | Add Integration Picker | AddIntegrationViewModel |
| T-14d | Integration Setup flow (notification prefs, cert spinner, URL screen) | IntegrationSetupViewModel |
| T-14e | Integration Manage | IntegrationManageViewModel |
| T-14f | Device Code Approval + Connected | DeviceCodeApprovalViewModel |
| T-14g | Audit History | AuditHistoryViewModel |
| T-14h | Settings | SettingsViewModel |
| T-14i | Navigation host + bottom nav + deep links | - |

**Tests first (per sub-task):**
- ViewModel unit tests: mock dependencies, verify state emissions for all input combinations
- Navigation flow tests: test IDs 224-241 from overall.md

**Acceptance criteria:**
- All screens match UI wireframes in ui.md
- Navigation flows match the flow diagram in ui.md
- Bottom nav with 3 tabs: Home, Audit, Settings
- Deep-link from notification to audit history filtered by sessionId
- All ViewModel tests pass

---

### Phase 6: Cross-Module Integration Tests (1-2 agents)

#### Task T-15: Tunnel + MCP Integration Test

**Owner:** tunnel agent or mcp agent
**Branch:** `feat/integration-tunnel-mcp`
**Depends on:** T-5, T-2

End-to-end test: OPEN frame -> tunnel TLS accept -> plaintext stream -> mcp-core Ktor serves HTTP -> client gets MCP tool response. Test IDs 97-99 from overall.md.

#### Task T-16: Tunnel + Relay Integration Test

**Owner:** relay agent + tunnel agent
**Branch:** `feat/integration-tunnel-relay`
**Depends on:** T-5, T-10

Cross-language integration test: Kotlin tunnel connects to Rust relay via real WebSocket + mTLS. Test IDs 100-104 from overall.md.

---

## Worktree Strategy

### Branch Naming

```
scaffold/gradle-restructure     # Phase 0
feat/tunnel-mux-framing         # T-1
feat/mcp-http-oauth             # T-2
feat/relay-scaffold             # T-3
feat/relay-api                  # T-4
feat/tunnel-websocket-tls       # T-5
feat/api-contract               # T-6
feat/notifications-model        # T-7
feat/work-service               # T-8
feat/tunnel-onboarding          # T-9
feat/relay-passthrough          # T-10
feat/security-monitoring         # T-11
feat/health-integration         # T-12
feat/app-koin-wiring            # T-13
feat/app-ui                     # T-14
feat/integration-tunnel-mcp     # T-15
feat/integration-tunnel-relay   # T-16
```

### Creating Worktrees

Each agent gets its own worktree branching from the latest integration point:

```bash
# Phase 0 — single agent
git worktree add ../rouse-scaffold scaffold/gradle-restructure

# Phase 1 — after S-1 merged to main
git worktree add ../rouse-tunnel-framing feat/tunnel-mux-framing
git worktree add ../rouse-mcp-oauth feat/mcp-http-oauth
git worktree add ../rouse-relay-scaffold feat/relay-scaffold

# Phase 2 — after relevant Phase 1 tasks merged
git worktree add ../rouse-tunnel-ws feat/tunnel-websocket-tls
git worktree add ../rouse-api-contract feat/api-contract
git worktree add ../rouse-notifications feat/notifications-model

# etc.
```

### Merge Strategy

1. **All branches created from `main`** at the latest stable point (after prerequisites merged).
2. **Each task branch merges to `main` via PR** with squash merge.
3. **Merge order within a phase does not matter** — tasks within a phase are independent.
4. **Before starting the next phase**, all prerequisite tasks from the current phase must be merged to `main`.
5. **Conflict avoidance by design**: each task touches different files. The only shared files are:
   - `settings.gradle.kts` — only modified in S-1
   - `gradle/libs.versions.toml` — modified in S-1, may need additions in later tasks (coordinate via PR review)
   - `build.gradle.kts` (root) — only modified in S-1

### Parallel Execution Limit

The project naturally supports up to 4 concurrent agents:
- **Relay agent** — works on `relay/` exclusively, never touches Kotlin files
- **Tunnel agent** — works on `core/tunnel/` exclusively
- **MCP agent** — works on `core/mcp/` exclusively
- **Android agent** — works on `:api`, `:notifications`, `:work`, `:health`, `:app`

The relay agent can run continuously from Phase 1 through Phase 3 without blocking on Kotlin tasks. The tunnel and MCP agents can run continuously from Phase 1 through Phase 3. The Android agent picks up work from Phase 2 onward.

## Task Dependency DAG

```
S-1 (scaffold)
 |
 ├── T-1 (tunnel framing) ──── T-5 (tunnel WS+TLS) ──── T-9 (onboarding) ──┐
 │                                    |                                       │
 │                                    ├── T-14 (tunnel+mcp integration) ──── T-15
 │                                    |                                       │
 ├── T-2 (mcp HTTP+OAuth) ───────────┘                                       │
 │         |                                                                  │
 │         └── T-6 (api contract) ──── T-11 (health) ──┐                     │
 │                                                       │                    │
 ├── T-3 (relay scaffold) ── T-4 (relay API) ── T-10 (relay passthrough) ────┘
 │                                                       │
 │                                                       │
 ├── T-7 (notifications) ── T-8 (work service) ─────────┤
 │                                                       │
 └───────────────────────────────────── T-12 (app wiring) ── T-13 (app UI)
```

## Summary: Task Quick Reference

| ID | Task | Phase | Owner | Depends On | Key Module |
|----|------|-------|-------|------------|------------|
| S-1 | Gradle restructure | 0 | any | - | root, all |
| T-1 | Mux framing & protocol | 1 | tunnel | S-1 | `:core:tunnel` |
| T-2 | HTTP server, OAuth, tokens | 1 | mcp | S-1 | `:core:mcp` |
| T-3 | Relay scaffold + SNI router | 1 | relay | S-1 | `relay/` |
| T-4 | Relay API + FCM + Firestore | 1 | relay | T-3 | `relay/` |
| T-5 | WebSocket client + TLS accept | 2 | tunnel | T-1 | `:core:tunnel` |
| T-6 | Provider contract + state store | 2 | android | T-2 | `:api` |
| T-7 | NotificationModel + audit | 2 | android | T-1, T-2 | `:notifications` |
| T-8 | Foreground service + wakelock | 3 | android | T-5, T-7 | `:work` |
| T-9 | Onboarding + cert renewal | 3 | tunnel | T-5 | `:core:tunnel` |
| T-10 | Client passthrough + shutdown | 3 | relay | T-4 | `relay/` |
| T-11 | Health Connect integration | 4 | android | T-6 | `:health` |
| T-12 | Koin wiring + implementations | 5 | android | T-5, T-6, T-7, T-8 | `:app` |
| T-13 | Compose UI + navigation | 5 | android | T-12 | `:app` |
| T-14 | Tunnel + MCP integration test | 6 | any | T-5, T-2 | cross-module |
| T-15 | Tunnel + Relay integration test | 6 | any | T-5, T-10 | cross-module |

## Agent Assignment Summary

| Agent | Tasks (in order) | Phases Active |
|-------|-----------------|---------------|
| Scaffold | S-1 | 0 |
| Relay | T-3 → T-4 → T-10 | 1, 1, 3 |
| Tunnel | T-1 → T-5 → T-9 | 1, 2, 3 |
| MCP | T-2 (then available for T-14) | 1, 6 |
| Android | T-6 → T-7 → T-8 → T-11 → T-12 → T-13 | 2, 2, 3, 4, 5, 5 |
