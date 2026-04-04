# Overall System Design

## System Architecture

Three components, two TLS layers:

```
MCP Client ──TLS (inner, passthrough)──> Relay ──mux WebSocket (outer, mTLS)──> Device
```

- **Inner TLS**: client↔device, end-to-end encrypted MCP traffic. Relay never terminates, never reads.
- **Outer TLS**: device↔relay mux WebSocket, relay terminates. mTLS authenticates device by cert.

## Device Identity Model

Each device is a unique anonymous identity. No user accounts, no sign-in, no cross-device transfer.

- **Anonymous Firebase Auth** — unique UID per install. Lost on uninstall.
- **Android Keystore keypair** — ECDSA P-256, hardware-backed, private key never leaves secure element.
- **Subdomain** — assigned once at onboarding, tied to Firebase UID + public key.
- **Uninstall+reinstall = new device.** Old subdomain expires after TTL.
- **Re-registration** (same Firebase UID, e.g. app data cleared): requires both Firebase token AND signature with original private key. If either is lost, device gets new subdomain. UI should explain this clearly.
- **Subdomain rotation**: user can request a new subdomain in Settings (once per 30 days). Old subdomain invalidated immediately, all client tokens revoked.

### Cryptography

- **Key type**: ECDSA P-256 (secp256r1)
- **Signature algorithm**: SHA256withECDSA
- **Public key format in Firestore**: Base64-encoded DER (SubjectPublicKeyInfo)
- **Signature format in API requests**: Base64-encoded DER

Auth methods by phase:

| Phase | Auth method |
|---|---|
| Onboarding (no cert) | Firebase ID token (JWT) |
| Normal operation | mTLS with device cert |
| Cert renewal (valid cert) | mTLS via HTTPS `POST /renew` |
| Cert renewal (expired cert) | Firebase token + SHA256withECDSA signature over CSR |

## Firestore Data Model

```
devices/{subdomain}
  fcm_token: string
  firebase_uid: string
  public_key: string          // Base64 DER SubjectPublicKeyInfo, from original CSR
  cert_expires: timestamp
  registered_at: timestamp
  last_rotation: timestamp    // for enforcing 30-day subdomain rotation cooldown
  renewal_nudge_sent: timestamp | null  // set when 7-day FCM sent, cleared on successful renewal

pending_certs/{subdomain}     // devices blocked on ACME rate limits
  fcm_token: string
  csr: string
  blocked_at: timestamp
  retry_after: timestamp
```

Security rules: device can only write its own `devices/` document (matched by firebase_uid for FCM token updates). Relay reads/writes all via service account.

## End-to-End Flows

### Onboarding (first run)

1. App generates ECDSA P-256 keypair in Android Keystore
2. App registers with Firebase anonymous auth → gets UID + FCM token
3. App creates PKCS#10 CSR (signed by Keystore private key, via Bouncy Castle)
4. App calls relay: `POST /register` with Firebase ID token + CSR + FCM token
5. Relay assigns random two-word subdomain, performs DNS-01 ACME challenge via Cloudflare
6. Relay stores device record in Firestore
7. Relay returns cert + subdomain to device
8. Device stores cert as PEM in app-private storage, subdomain + metadata in `device.json`

### Normal session (cold — device not connected)

1. MCP client connects to `brave-falcon.rousecontext.com:443`
2. Relay parses SNI from TLS ClientHello (buffers the ClientHello)
3. Relay looks up device (Firestore, cached in memory with TTL), no active mux → fires FCM
4. Relay holds client TCP, waits up to 20s (configurable)
5. Device wakes, opens mux WebSocket with mTLS (device cert)
6. Relay verifies cert (CA-signed, extracts subdomain from CN/SAN), associates mux with device
7. Relay assigns stream ID, sends OPEN frame with SNI hostname
8. Relay forwards buffered ClientHello as DATA frame
9. Device demuxes, TLS handshake completes client↔device (inside DATA frames)
10. MCP session active
11. On end: CLOSE frame, teardown

### Normal session (warm — device already connected)

Steps 3-6 skipped — relay immediately sends OPEN. No FCM, no wait.

### Wake pre-flight

1. Client calls `POST /wake/brave-falcon`
2. Relay checks if device has active mux → if yes, return 200 immediately
3. If not: fire FCM, wait for device to connect (up to 20s)
4. Return 200 when connected, 504 on timeout
5. Client then opens TLS connection — gets instant session

### Cert renewal

Two-stage proactive renewal:

1. **14 days to expiry**: WorkManager daily check triggers renewal via `POST /renew` (mTLS auth). Silent unless it fails.
2. **7 days to expiry**: If first renewal failed, relay sends FCM push prompting immediate renewal. Warning notification to user.
3. **Expired**: Device cannot mTLS. Renews via `POST /renew` with Firebase token + signature. Error notification: "Certificate expired, renewing..." Incoming client connections fail until renewed.

Renewal flow (valid cert):
1. Device calls `POST /renew` with mTLS client cert auth + new CSR
2. Relay verifies cert, performs ACME challenge, returns new cert

Renewal flow (expired cert):
1. Device calls `POST /renew` with subdomain, new CSR, Firebase token, signature over CSR
2. Relay verifies Firebase UID match + signature against stored public key
3. Relay performs ACME challenge, returns new cert
4. Device stores new cert, reconnects

### Subdomain rotation

1. User taps "Generate new address" in Settings
2. App shows confirmation: "All connected clients will lose access. You'll need to re-add the new URL. You can only do this once every 30 days."
3. App calls `POST /register` with `"force_new": true` + Firebase token + signature
4. Relay verifies identity, assigns new subdomain, performs ACME, invalidates old subdomain
5. All client tokens revoked
6. App shows new subdomain + URLs

### FCM payload format

```json
{
  "message": {
    "token": "{device_fcm_token}",
    "android": { "priority": "high" },
    "data": {
      "type": "wake",
      "relay_host": "relay.rousecontext.com",
      "relay_port": "443"
    }
  }
}
```

Data-only message (no `notification` block) so `FirebaseMessagingService` always receives it. `type` field extensible for future message types.

## MCP Client Authentication (Device Code Flow)

OAuth 2.1 device authorization grant (RFC 8628). **Per-integration auth** — each integration is an independent MCP server with its own OAuth endpoints, device codes, and tokens. The device hosts OAuth inside the TLS tunnel — relay is not involved.

### Flow

1. MCP client connects to `brave-falcon.rousecontext.com/health` (triggers FCM wakeup if needed)
2. Client requests `GET /health/.well-known/oauth-authorization-server`
3. Device returns metadata for the `/health` integration
4. Client calls `POST /health/device/authorize`
5. Device generates device_code (opaque, 32 bytes base64url) + user_code (8 chars alphanumeric uppercase, no 0/O/1/I/L, displayed as `XXXX-XXXX`), returns both + polling interval (5s)
6. Device shows notification: "Claude wants to access **Health Connect**"
7. User opens app, enters user_code, taps Approve
8. Client polls `POST /health/token` with device_code every 5s until approved
9. Device returns access_token (opaque, 32 bytes base64url, no expiry — valid until revoked)
10. Client uses Bearer token for all subsequent MCP requests to `/health`

A client authorized for `/health` does NOT have access to `/notifications`. Each integration requires its own auth flow.

### Device code TTL: 10 minutes

Polling responses: `authorization_pending` (keep polling), `slow_down` (increase interval), `expired_token` (10 min elapsed), `access_denied` (user denied).

### Token Management (on device)

- Tokens stored locally (Room database)
- Each token: integration_id, client_id, access_token_hash, created_at, last_used_at, label
- Tokens scoped to integration — a token for `/health` can't access `/notifications`
- User can view and revoke tokens per-integration in app UI
- Token verification: device checks Bearer header on every request, scoped to the integration path
- All tokens revoked on subdomain rotation

### Where This Lives

`:mcp-core` — HTTP routing, per-integration OAuth endpoints, token management, auth verification. The tunnel provides the byte stream (plaintext after TLS termination), mcp-core handles everything on top of it.

## mcp-core HTTP Server

The tunnel hands `MuxStream` (plaintext `InputStream`/`OutputStream` after inner TLS termination) to the app. The app passes it to mcp-core, which runs a Ktor HTTP server over the stream.

### Protocol

**Streamable HTTP** per MCP spec (2025-03-26). Client sends HTTP POST requests, server responds with JSON. SSE for server-initiated messages if needed. This is the standard remote MCP transport — stock MCP clients support it natively.

### Stack on device

```
MuxStream (raw bytes from relay)
  → TLS server accept (tunnel, using device cert)
  → plaintext InputStream/OutputStream
  → Ktor embedded HTTP server (mcp-core)
  → route by first path segment to integration:
      /health/.well-known/oauth-authorization-server → health's OAuth metadata
      /health/device/authorize → health's device code flow
      /health/token → health's token exchange
      /health/* → health's MCP Streamable HTTP
      /notifications/* → notifications' OAuth + MCP (same pattern)
      unknown path → 404
```

### Auth per integration

Each integration path prefix is a self-contained MCP server with its own OAuth. Ktor routes by first path segment, then each integration handler checks its own Bearer token. Returns 401 with `WWW-Authenticate` pointing to that integration's OAuth metadata if token missing or invalid.

### mcp-core Interfaces

```kotlin
/** Live registry of enabled integrations. App implements, mcp-core queries per-request. */
interface ProviderRegistry {
    fun providerForPath(path: String): McpServerProvider?
    fun enabledPaths(): Set<String>
}

/** Token verification/management, scoped per integration. App implements backed by Room. */
interface TokenStore {
    fun validateToken(integrationId: String, token: String): Boolean
    fun createToken(integrationId: String, clientId: String): String
    fun revokeToken(integrationId: String, token: String)
    fun listTokens(integrationId: String): List<TokenInfo>
    fun hasTokens(integrationId: String): Boolean  // for Pending vs Active state
}

class McpSession(
    private val providers: ProviderRegistry,
    private val tokenStore: TokenStore,
    private val auditListener: AuditListener? = null,
) {
    /** Runs Ktor HTTP server over the stream. Suspends until stream closes. */
    suspend fun run(input: InputStream, output: OutputStream)
}
```

`McpSession` is long-lived and shared across streams. The app calls `session.run(stream.input, stream.output)` for each incoming `MuxStream`. Provider changes (enable/disable) are reflected immediately via `ProviderRegistry` — no session reconstruction needed.

### Per-stream HTTP server

Each `run()` call creates a lightweight Ktor server instance over the provided I/O. When the stream closes, the server instance is disposed. Shared state across streams: `ProviderRegistry`, `TokenStore`, `AuditListener`.

### OAuth metadata response (RFC 8414) — per integration

Example for `/health`:
```json
{
  "issuer": "https://brave-falcon.rousecontext.com/health",
  "device_authorization_endpoint": "https://brave-falcon.rousecontext.com/health/device/authorize",
  "token_endpoint": "https://brave-falcon.rousecontext.com/health/token",
  "grant_types_supported": ["urn:ietf:params:oauth:grant-type:device_code"],
  "response_types_supported": [],
  "code_challenge_methods_supported": []
}
```

Each integration has its own issuer and endpoints. Generated from subdomain + integration path.

## Integration Model

Each `McpServerProvider` is exposed as a separate MCP server at its own URL path:

```
https://brave-falcon.rousecontext.com/health          → Health Connect
https://brave-falcon.rousecontext.com/notifications    → Notifications (future)
https://brave-falcon.rousecontext.com/contacts         → Contacts (future)
```

### How It Works

- All paths share one subdomain, one TLS cert, one mux connection
- Relay can't see paths (inside TLS) — creates a new mux stream per client TCP connection, routed by SNI
- Device's HTTP server (in `:mcp-core`) decrypts TLS, reads request path, routes to correct provider
- Each path is an independent MCP session from the client's perspective
- Max 8 concurrent streams per device (configurable on relay)

### Auth

**Per-integration.** Each integration is an independent MCP server with its own OAuth flow, device codes, and tokens. A client authorized for `/health` has no access to `/notifications`.

User controls exposure by:
1. Which integration URLs they share with their MCP client
2. Enabling/disabling integrations in-app (disabled integration returns 404)
3. Revoking tokens per-integration in the authorized clients UI

### Path Routing in mcp-core

- `/{integration}/.well-known/oauth-authorization-server` → that integration's OAuth metadata
- `/{integration}/device/authorize` → that integration's device code flow
- `/{integration}/token` → that integration's token exchange
- `/{integration}/*` → that integration's MCP Streamable HTTP (auth required)
- Unknown or disabled path → 404
- 404 on disabled integration also triggers app notification (audit trail)

### User Experience

1. User enables integration (e.g. "Health Connect") in app
2. App requests necessary permissions
3. App shows the integration URL with copy button / share sheet
4. User adds URL to their MCP client
5. First connection triggers device code auth (if not already authorized)
6. Integration is live

## ACME Rate Limit Handling

### Primary CA: Let's Encrypt (50 certs per registered domain per week)

### When limit is hit:
1. Relay returns `{"error": "rate_limited", "retry_after_secs": 604800}` to device
2. Relay stores blocked device in `pending_certs/{subdomain}` with FCM token
3. Relay sends automated admin alert (email/webhook, configured in relay.toml)
4. Device shows notification: "Certificate issuance temporarily delayed. Will retry automatically on [date]."
5. Device schedules WorkManager retry for `retry_after` time

### When quota resets:
1. Relay processes pending queue
2. Relay sends FCM to each blocked device to trigger cert pickup

### Future: add Google Trust Services or ZeroSSL as fallback CA

## Module Structure

Both `:mcp-core` and `:tunnel` are **Kotlin Multiplatform** with `jvm` + `android` targets. Since both targets are JVM-based, all code (including MCP SDK types) is accessible from `commonMain`. `jvmTest` enables integration tests without Android test runner.

- `:mcp-core` (KMP: jvm + android) — MCP SDK, OAuth server (device code flow), token management, HTTP routing + path-based provider dispatch, `McpSession`, `McpServerProvider`
- `:tunnel` (KMP: jvm + android) — mux WebSocket client, framing, stream demux. `androidMain`: FCM, Keystore, WorkManager
- `:mcp-health` (Android library) — Health Connect, depends on `:mcp-core`
- `:app` (Android application) — Compose UI, foreground service, audit persistence, integration management, wakelock, notifications
- `relay/` (Rust, Cargo) — independent, no Kotlin/Android deps

## Subdomain Format

Two-word combination: `{adjective}-{noun}.rousecontext.com` (e.g. `brave-falcon.rousecontext.com`).

- Word lists: ~2000 adjectives, ~2000 nouns → ~4M combinations
- Lowercase alphanumeric + hyphen only (DNS-safe)
- Collision avoidance: generate, check Firestore, retry if taken
- NOT a secret — security comes from device code auth + TLS

### TTL / Reclamation

Abandoned subdomains reclaimed **180 days** after cert expiry with no renewal attempt. Relay periodic cleanup job.

### Rotation

User can request new subdomain once per 30 days. Old subdomain invalidated immediately. All client tokens revoked.

## Integration Test Scenarios

### Onboarding
1. Happy path: fresh install → Firebase auth → keypair → CSR → cert received → Firestore record created
2. Firestore unavailable during registration → graceful failure, retry
3. ACME challenge failure → error returned, device retries with exponential backoff
4. Re-registration (same Firebase UID + valid key signature) → same subdomain, new cert

### Normal sessions
5. Cold path: client → SNI → FCM → device wakes → mux → OPEN → TLS handshake → MCP tool call → response → CLOSE
6. Warm path: client → SNI → device already connected → OPEN → instant session
7. Concurrent clients: two clients to same device → two streams, both work independently
8. Client disconnects mid-session → relay sends CLOSE → device tears down McpSession
9. Device disconnects mid-session → relay closes client TCP
10. Max concurrent streams exceeded → relay sends ERROR(STREAM_REFUSED) for new OPEN

### Wake pre-flight
11. `/wake` for offline device → FCM sent → device connects → 200
12. `/wake` for already-connected device → immediate 200
13. `/wake` spammed > 6 times in 1 minute → 429 rate limited

### Edge cases
14. FCM timeout (device doesn't connect within 20s) → relay closes client connection
15. Device sends ERROR(STREAM_REFUSED) → relay closes client
16. Device sends DATA for unknown stream_id → relay sends ERROR(UNKNOWN_STREAM)
17. Mux WebSocket drops during active sessions → relay closes all client connections for that device
18. No mid-stream reconnect — clean teardown, client retries from scratch
19. Relay restart → all mux connections drop → devices reconnect on next FCM

### Cert renewal
20. Proactive renewal at 14 days → new cert issued, silent
21. Second chance at 7 days → relay FCM push (`type: "renew"`, normal priority), warning notification
22. Relay only nudges once per expiry cycle (tracks `renewal_nudge_sent`, cleared on successful renewal)
23. Expired cert → device renews via Firebase + signature path → new cert → reconnects
24. Expired renewal with wrong Firebase UID → rejected
25. Expired renewal with invalid signature → rejected
26. ACME failure during renewal → error, device retries with backoff
27. Device validates renewed cert CN/SAN matches stored subdomain before storing

### ACME rate limits
28. Registration hits Let's Encrypt rate limit → device gets `rate_limited` error + retry_after
29. Device shows "delayed" notification, schedules retry
30. Admin receives automated alert
31. Quota resets → relay processes pending queue, sends FCM to blocked devices
32. Subdomain rotation hits rate limit → same handling as registration

### FCM token refresh
33. Token rotates → device updates Firestore → next FCM uses new token
34. Token rotates during active mux → Firestore updated, no session disruption

### Security
35. Mux `/ws` with no client cert → 401 rejected
36. Mux `/ws` with invalid/untrusted client cert → rejected
37. Mux `/ws` with valid client cert → mux established
38. `/register` with no client cert → accepted (Firebase token auth)
39. `/renew` with valid client cert → mTLS auth path used
40. `/renew` with no client cert → Firebase + signature auth path used
41. `/renew` with expired client cert → falls back to Firebase + signature path
42. Mux with cert for different subdomain → maps to that subdomain, can't spoof another
43. `/register` with stolen Firebase token but different keypair → new subdomain, can't steal existing
44. Replayed old CSR to `/renew` → signature mismatch, rejected

### OAuth / device code auth (per-integration)
45. First connection to `/health` — no token → 401 → discovery at `/health/.well-known/...` → device code → token → MCP works
46. Subsequent connection to `/health` — valid token → immediate MCP session
47. Revoked token for `/health` → 401 → re-auth for `/health`
48. Expired device_code (10 min) → client gets `expired_token`, retries
49. Multiple authorized clients for same integration — each has own token, revoking one doesn't affect others
50. Concurrent device code requests from two clients to same integration → independent codes, independent approvals
51. Token revoked during active session → next request gets 401, current request completes
52. Client authorized for `/health` tries `/notifications` → 401 (tokens don't cross integrations)
53. Device code approval notification shows integration name ("Claude wants to access Health Connect")

### Integration routing
54. Client connects to `/health` → routes to Health Connect provider
55. Two clients to different integration paths simultaneously → independent sessions
56. Disabled integration → 404, app notification triggered
57. Auth token for `/health` works for all requests under `/health/*`
58. Unknown path → 404

### Subdomain rotation
59. Rotation succeeds → new subdomain, old invalidated, ALL tokens (all integrations) revoked
60. Second rotation within 30 days → rejected by relay
61. Rotation during active sessions → all sessions torn down, clients reconnect to new subdomain
62. Rotation spam protection → relay enforces 30-day cooldown server-side

### Relay: subdomain generation
63. Generated subdomain is two-word format (adjective-noun, lowercase, hyphen-separated)
64. Generated subdomain is DNS-safe (alphanumeric + hyphen only)
65. Collision detected → retry with new words, different subdomain returned
66. Multiple concurrent registrations → no duplicate subdomains

### Relay: graceful shutdown
67. SIGTERM → all active streams receive CLOSE
68. SIGTERM → all mux WebSockets closed after drain timeout (5s)
69. SIGTERM → in-flight DATA frames drain before close
70. SIGTERM → no new client TCP connections accepted during shutdown
71. Shutdown stats logged (sessions closed, mux connections dropped)

### Relay: /status endpoint
72. Returns correct active_mux_connections count
73. Returns correct active_streams count
74. Returns correct total_sessions_served (increments over time)
75. Returns correct pending_fcm_wakeups count
76. Returns uptime_secs (monotonically increasing)

### Relay: daily maintenance job
77. Cert expiry nudge: device with cert_expires < now+7d and no nudge_sent → FCM type:"renew" sent, nudge_sent set
78. Cert expiry nudge: device already nudged (nudge_sent set) → no duplicate FCM
79. Cert expiry nudge: device renewed after nudge → nudge_sent cleared, won't be nudged again until next cycle
80. Pending cert queue: rate limit reset → pending devices processed, certs issued, FCM sent
81. Pending cert queue: rate limit not yet reset → no processing
82. Subdomain reclamation: cert_expires > 180 days ago, no renewal → device record deleted
83. Subdomain reclamation: cert_expires > 180 days ago, recent renewal attempt → NOT deleted

### Relay: config
84. TOML config file parsed correctly (listen_port, domain, timeouts, acme)
85. Missing config file → sensible defaults + error log
86. Env var overrides (CLOUDFLARE_API_TOKEN, FIREBASE_SERVICE_ACCOUNT_JSON) loaded
87. Missing required env var → relay fails to start with clear error message

### Cross-module integration (tunnel + mcp-core)
88. OPEN frame → tunnel TLS accept → plaintext stream → mcp-core Ktor serves HTTP → client gets MCP tool response (full stack)
89. Client HTTP request spans multiple mux DATA frames → mcp-core reassembles correctly
90. mcp-core sends large response → tunnel frames as multiple DATA frames → client reassembles

### Cross-module integration (tunnel + relay)
91. Tunnel connects to real relay → mTLS handshake → OPEN received → DATA bidirectional → CLOSE
92. Relay sends OPEN while tunnel is processing previous OPEN → both streams created correctly
93. Relay sends DATA after tunnel already sent CLOSE for that stream → tunnel ignores, no crash

### Cross-module integration (cert flow: app → CertificateStore → tunnel → relay)
94. Fresh cert from onboarding → stored via CertificateStore → tunnel uses for mux mTLS → relay accepts
95. Renewed cert → stored → tunnel uses new cert on next mux → relay accepts

### mcp-core: HTTP server + MCP protocol
96. `GET /health/.well-known/oauth-authorization-server` → valid RFC 8414 metadata for health integration
97. MCP `initialize` via Streamable HTTP POST to `/health` → server responds with capabilities
98. `tools/list` to `/health` → returns only Health Connect tools
99. `tools/call` with valid args → tool executes, result returned
100. `tools/call` with unknown tool name → MCP error response
101. `resources/list` → returns resources from targeted provider only
102. `resources/read` with valid URI → resource content returned
103. Malformed JSON-RPC → HTTP 400 or MCP parse error
104. Provider throws exception during tool call → MCP error response, audit logged, stream stays open

### mcp-core: OAuth device code flow (per-integration)
105. `POST /health/device/authorize` → returns device_code, user_code, interval
106. `POST /health/token` before approval → `authorization_pending`
107. `POST /health/token` after approval → access_token scoped to `/health`
108. `POST /health/token` after denial → `access_denied`
109. `POST /health/token` after 10 min → `expired_token`
110. `POST /health/token` with invalid device_code → error
111. Multiple pending device codes for same integration → each tracked independently
112. Multiple pending device codes across different integrations → independent
113. Expired device codes pruned, no memory leak

### mcp-core: auth middleware (per-integration)
114. Request to `/health/*` without Bearer → 401 with WWW-Authenticate pointing to `/health/.well-known/...`
115. Request to `/health/*` with valid `/health` Bearer → passes through to MCP handler
116. Request to `/health/*` with `/notifications` Bearer → 401 (wrong integration)
117. Request to `/health/*` with invalid/revoked Bearer → 401
118. OAuth endpoints (`/{integration}/.well-known/*`, `/{integration}/device/authorize`, `/{integration}/token`) accessible without Bearer
119. Token `last_used_at` updated on successful authenticated request

### mcp-core: ProviderRegistry + IntegrationStateStore
120. Integration user-enabled → path returns MCP tools
121. Integration user-disabled mid-session → next request returns 404
122. Integration re-enabled → path works again
123. `enabledPaths()` reflects current IntegrationStateStore state
124. Unknown path → 404 regardless of registry state

### mcp-core: per-stream HTTP server lifecycle
125. Stream closes cleanly → Ktor server disposed, no resource leak
126. Stream closes mid-request → server handles gracefully
127. Multiple concurrent streams → independent HTTP servers, no cross-contamination

### Tunnel: mux frame parser
128. Valid DATA frame → payload routed to correct stream
129. Valid OPEN frame → new MuxStream emitted with correct sniHostname and generated sessionId UUID
130. Valid CLOSE frame → stream closed, app notified
131. Valid ERROR frame → TunnelError emitted with correct code and message
132. Frame with unknown type byte → logged, ignored
133. Incomplete frame (WebSocket message too short) → error, other streams unaffected
134. OPEN for stream ID that already exists → error logged, ignored

### Tunnel: mux outbound
135. App writes to MuxStream.output → framed as DATA with correct stream ID
136. MuxStream.close() → CLOSE frame sent
137. Multiple streams writing concurrently → frames interleaved correctly, no corruption

### Tunnel: TLS server accept
138. Valid TLS ClientHello → handshake completes, MuxStream emits plaintext
139. Invalid TLS from client → handshake fails, stream closed with ERROR(STREAM_REFUSED)
140. CertificateStore returns null cert → TunnelError.CertExpired, stream refused
141. Cert doesn't match private key → TLS fails, error emitted

### Tunnel: CertificateStore integration
142. Valid cert → mTLS to relay succeeds
143. Null cert → tunnel emits CertExpired, doesn't attempt mux
144. getCertExpiry() used by WorkManager for renewal timing
145. storeCertChain() after renewal → subsequent connections use new cert

### Tunnel: state machine
146. DISCONNECTED → connect() → CONNECTING → WebSocket opens → CONNECTED
147. CONNECTED → OPEN arrives → ACTIVE
148. ACTIVE → last stream closes → CONNECTED
149. CONNECTED → disconnect() → DISCONNECTING → CLOSE all → DISCONNECTED
150. ACTIVE → WebSocket drops → all streams torn down → DISCONNECTED
151. CONNECTING → handshake fails → DISCONNECTED + ConnectionFailed
152. CONNECTING → mTLS rejected → DISCONNECTED + AuthRejected

### Tunnel: FCM dispatch
153. FCM `type: "wake"` → TunnelService started, mux connection opened
154. FCM `type: "renew"` → cert renewal triggered, no mux connection
155. FCM unknown type → logged, ignored

### Tunnel: cert renewal execution
156. Renewal with valid mTLS → POST /renew, new cert received and stored
157. Renewal with expired cert → POST /renew with Firebase token + signature, new cert stored
158. Renewed cert CN/SAN mismatch → cert rejected, error logged
159. Relay returns rate_limited → retry scheduled for retry_after
160. Relay returns 5xx → exponential backoff retry
161. Network unavailable → WorkManager retry with network constraint
162. Renewal succeeds during active mux → new cert stored, current mux unaffected

### Tunnel: onboarding execution
163. Full onboarding: keypair → CSR → /register → cert + subdomain stored
164. Relay unreachable during onboarding → error surfaced to app
165. Rate limited during onboarding → rate_limited surfaced with retry_after
166. Partial failure (keypair generated, /register fails) → no partial state, clean retry

### :notifications — NotificationModel + audit
167. MuxConnected → ShowForeground
168. MuxDisconnected with tool calls + setting=Summary → PostSummary
169. MuxDisconnected with tool calls + setting=Each usage → individual PostToolUsage per call
170. MuxDisconnected with tool calls + setting=Suppress → no post-session notification
171. MuxDisconnected with zero tool calls → PostWarning("Roused with no usage")
172. StreamOpened → foreground notification updates stream count
173. StreamClosed (last stream) → DismissForeground
174. ErrorOccurred (stream-level) → silent, audit only
175. ErrorOccurred (connection-level) → PostError
176. Notification permission denied → post_session forced to Suppress, foreground still works
177. Audit entry persisted on tool call with all fields (timestamp, tool, args, result, duration, sessionId, providerId)
178. Audit query by sessionId → correct entries (notification deep-link target)
179. Audit query by date range + provider → filter works
180. Audit retention: >30 day entries pruned on launch
181. Audit empty state → no crash

### :work — service lifecycle, wakelock, WorkManager
182. FCM `wake` → foreground service starts → TunnelClient.connect() called
183. FCM `renew` → WorkManager renewal enqueued, no service start
184. Foreground service posts notification via createForegroundNotification()
185. TunnelState ACTIVE → wakelock acquired
186. TunnelState CONNECTED (from ACTIVE) → wakelock released
187. TunnelState CONNECTING → wakelock acquired
188. TunnelState DISCONNECTED → wakelock released, no leak
189. Rapid state transitions → wakelock balanced (no leak)
190. Idle timeout: CONNECTED for N minutes → disconnect() called
191. Idle timeout cancelled: stream opens before expiry
192. Idle timeout disabled when battery optimization exempt
193. Idle timeout cannot be disabled without exemption
194. WorkManager cert renewal: <14 days → POST /renew triggered
195. WorkManager cert renewal: cert not expiring → no-op
196. FCM token refresh → Firestore updated

### :api + :health — integration contract
197. HealthConnectIntegration.isAvailable() → true when Health Connect installed, false when not
198. IntegrationStateStore.setUserEnabled("health", true) → ProviderRegistry exposes /health
199. IntegrationStateStore.setUserEnabled("health", false) → ProviderRegistry returns null for /health
200. Integration onboarding route completes → app sets userEnabled=true, state transitions to Pending
201. Integration onboarding cancelled → state unchanged (Available)
202. Permissions revoked externally → integration surfaces banner on its settings screen, MCP tools return errors for affected data types

### :app — wiring, navigation, integration management
203. App launch after onboarding → main screen shown
204. App launch before onboarding → onboarding flow shown
205. Integration enabled → ProviderRegistry updated → path serves MCP
206. Integration disabled → ProviderRegistry updated → path returns 404
207. Device code approval: correct code → token issued for that integration
208. Device code approval: wrong code → error, retry
209. Device code denial → access_denied to client
210. Client revoked for integration → token invalid for that integration immediately
211. Subdomain rotation → confirmation → new subdomain, all tokens revoked, UI updated
212. Rotation within 30 days → rejected
213. Battery optimization card shown when not exempt, dismissable
214. Notification permission denied at first integration setup → forced suppress setting

### UI: navigation flows (Compose host-side JVM tests)
215. First launch → Welcome → Get Started → Dashboard with cert onboarding banner
216. First integration: Dashboard → [+ Add] → Picker → Health Connect → Notification Prefs → integration/health/setup
217. First integration (suppress notifications): select Suppress → no system permission dialog → integration/health/setup
218. Cert spinner: integration setup completes → Setting Up shown → auto-advances when cert valid
219. Integration Enabled → Device Code auto-navigate: on URL screen → device code arrives → Device Code Approval
220. Device Code → Connected → Dashboard: enter code → Approve → Connected → Back to Home → Active on dashboard
221. Second integration: Dashboard → [+ Add] → skips Notification Prefs → integration setup directly
222. Re-enable disabled: Add picker → tap disabled → may skip setup → Pending on dashboard
223. Pending integration tap → Integration Manage (Pending variant) or Setting Up (cert issuing)
224. Active integration tap → Integration Manage (Active variant)
225. Integration Manage → Settings → integration/{id}/settings (integration-owned)
226. Device code from notification: notification intent → Device Code Approval
227. Audit deep-link: notification intent with sessionId → Audit History filtered
228. Subdomain rotation: Settings → Generate new address → confirm → new subdomain
229. Rotation cooldown: within 30 days → button disabled or error

### UI: bottom nav
230. Dashboard → Audit tab → Audit History
231. Dashboard → Settings tab → Settings
232. Tab state preserved on return

### UI: dashboard states (ViewModel-driven)
233. CertStatus.None → onboarding banner with progress
234. CertStatus.Onboarding(GENERATING_KEYS) → "Generating keys..."
235. CertStatus.Onboarding(ISSUING_CERT) → "Issuing certificate..."
236. CertStatus.Valid → no banner
237. CertStatus.Expired(renewalInProgress=true) → "Renewing..."
238. CertStatus.Expired(renewalInProgress=false) → error banner with Retry
239. CertStatus.RateLimited → delayed banner with date
240. No integrations → empty state with "Add your first" CTA
241. Pending + Active integrations → correct badges
242. [+ Add] hidden when all integrations enabled
243. Connection status: Disconnected / Connected / Active(N)
244. Recent activity preview, "View all" navigates to Audit

### UI: add integration picker
245. Shows Available + Disabled only
246. Unavailable greyed out
247. Disabled shows "Re-enable"
248. Set up navigates to notification prefs (first) or integration setup (subsequent)

### UI: integration manage screen
249. Active: shows URL, recent activity, authorized clients, Settings + Disable buttons
250. Pending: shows URL, "Waiting for first client", empty clients, Settings + Disable buttons
251. Revoke token → confirmation → token removed → list updates
252. Revoke last token → integration transitions Active → Pending
253. Disable → integration removed from dashboard, back in Add picker
254. Settings → navigates to integration/{id}/settings

### UI: settings
255. Idle timeout dropdown changes DataStore value
256. Disable timeout toggle only enabled when battery optimization exempt
257. Notification mode dropdown changes value
258. Subdomain rotation → confirmation dialog
259. Battery optimization card shown when not exempt, dismissed persisted

### UI: audit history
260. Entries rendered with timestamp, tool name, duration
261. Filter by provider
262. Filter by date range
263. Deep-link with sessionId pre-filters
264. Clear history → confirmation → entries deleted
265. Empty state

## Still Needs Design

1. **Third-party provider discovery** — bound service intent filter, verification, trust UI (future, not v1)
