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

OAuth 2.1 device authorization grant (RFC 8628). The device hosts the OAuth server inside the TLS tunnel — relay is not involved. Standard MCP clients discover auth via `/.well-known/oauth-authorization-server` per the MCP spec (2025-03-26).

### Flow

1. MCP client connects to `brave-falcon.rousecontext.com` (triggers FCM wakeup if needed)
2. Client requests `GET /.well-known/oauth-authorization-server`
3. Device returns metadata: `device_authorization_endpoint`, `token_endpoint`, `grant_types_supported: ["urn:ietf:params:oauth:grant-type:device_code"]`
4. Client calls `POST /device/authorize`
5. Device generates device_code (opaque, 32 bytes base64url) + user_code (8 chars alphanumeric uppercase, no 0/O/1/I/L, displayed as `XXXX-XXXX`), returns both + polling interval (5s)
6. Device shows notification: "A client wants to connect"
7. User opens app, enters user_code, taps Approve
8. Client polls `POST /token` with device_code every 5s until approved
9. Device returns access_token (opaque, 32 bytes base64url, no expiry — valid until revoked)
10. Client uses Bearer token for all subsequent MCP requests

### Device code TTL: 10 minutes

Polling responses: `authorization_pending` (keep polling), `slow_down` (increase interval), `expired_token` (10 min elapsed), `access_denied` (user denied).

### Token Management (on device)

- Tokens stored locally (Room database)
- Each token: client_id, access_token_hash, created_at, last_used_at, label
- User can view and revoke tokens in app UI
- Token verification: device checks Bearer header on every MCP request before processing
- Per-device auth — one approval covers all integration paths
- All tokens revoked on subdomain rotation

### Where This Lives

`:mcp-core` — HTTP routing (OAuth endpoints vs MCP protocol), token management, auth verification. The tunnel provides the byte stream (plaintext after TLS termination), mcp-core handles everything on top of it.

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
  → route by path:
      /.well-known/oauth-authorization-server → OAuth metadata
      /device/authorize → device code flow
      /token → token exchange
      /{integration}/* → MCP Streamable HTTP (dispatched to McpServerProvider)
```

### Auth middleware

Ktor interceptor checks Bearer token on all routes except OAuth endpoints (`/.well-known/*`, `/device/authorize`, `/token`). Returns 401 with `WWW-Authenticate` header pointing to OAuth metadata if token missing or invalid.

### mcp-core Interfaces

```kotlin
/** Live registry of enabled integrations. App implements, mcp-core queries per-request. */
interface ProviderRegistry {
    fun providerForPath(path: String): McpServerProvider?
    fun enabledPaths(): Set<String>
}

/** Token verification/management. App implements backed by Room. */
interface TokenStore {
    fun validateToken(token: String): Boolean
    fun createToken(clientId: String): String
    fun revokeToken(token: String)
    fun listTokens(): List<TokenInfo>
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

### OAuth metadata response (RFC 8414)

```json
{
  "issuer": "https://brave-falcon.rousecontext.com",
  "device_authorization_endpoint": "https://brave-falcon.rousecontext.com/device/authorize",
  "token_endpoint": "https://brave-falcon.rousecontext.com/token",
  "grant_types_supported": ["urn:ietf:params:oauth:grant-type:device_code"],
  "response_types_supported": [],
  "code_challenge_methods_supported": []
}
```

Issuer is the device's subdomain URL. Static per device.

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

Per-device, not per-integration. One device code approval authorizes the client for all paths. User controls exposure by:
1. Which integration URLs they share with their MCP client
2. Enabling/disabling integrations in-app (hard kill switch — disabled integration returns 404)

### Path Routing in mcp-core

- `/.well-known/oauth-authorization-server` → OAuth metadata (shared)
- `/device/authorize` → device code flow (shared)
- `/token` → token exchange (shared)
- `/{integration}/*` → routes to corresponding `McpServerProvider`
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

### OAuth / device code auth
45. First connection — no token → 401 → discovery → device code → token → MCP works
46. Subsequent connection — valid token → immediate MCP session
47. Revoked token → 401 → re-auth
48. Expired device_code (10 min) → client gets `expired_token`, retries
49. Multiple authorized clients — each has own token, revoking one doesn't affect others
50. Concurrent device code requests from two clients → independent codes, independent approvals
51. Token revoked during active session → next request gets 401, current request completes

### Integration routing
52. Client connects to `/health` → routes to Health Connect provider
53. Two clients to different integration paths simultaneously → independent sessions
54. Disabled integration → 404, app notification triggered
55. Auth token works across all integration paths on same device
56. Unknown path → 404

### Subdomain rotation
57. Rotation succeeds → new subdomain, old invalidated, tokens revoked
58. Second rotation within 30 days → rejected by relay
59. Rotation during active sessions → all sessions torn down, clients reconnect to new subdomain
60. Rotation spam protection → relay enforces 30-day cooldown server-side

### Cross-module integration (tunnel + mcp-core)
61. OPEN frame → tunnel TLS accept → plaintext stream → mcp-core Ktor serves HTTP → client gets MCP tool response (full stack)
62. Client HTTP request spans multiple mux DATA frames → mcp-core reassembles correctly
63. mcp-core sends large response → tunnel frames as multiple DATA frames → client reassembles

### Cross-module integration (tunnel + relay)
64. Tunnel connects to real relay → mTLS handshake → OPEN received → DATA bidirectional → CLOSE
65. Relay sends OPEN while tunnel is processing previous OPEN → both streams created correctly
66. Relay sends DATA after tunnel already sent CLOSE for that stream → tunnel ignores, no crash

### Cross-module integration (cert flow: app → CertificateStore → tunnel → relay)
67. Fresh cert from onboarding → stored via CertificateStore → tunnel uses for mux mTLS → relay accepts
68. Renewed cert → stored → tunnel uses new cert on next mux → relay accepts

### mcp-core: HTTP server + OAuth + MCP protocol
69. `GET /.well-known/oauth-authorization-server` → valid RFC 8414 metadata
70. MCP `initialize` via Streamable HTTP POST → server responds with capabilities
71. `tools/list` to `/health` → returns only Health Connect tools
72. `tools/call` with valid args → tool executes, result returned
73. `tools/call` with unknown tool name → MCP error response
74. `resources/list` → returns resources from targeted provider only
75. `resources/read` with valid URI → resource content returned
76. Malformed JSON-RPC → HTTP 400 or MCP parse error
77. Provider throws exception during tool call → MCP error response, audit logged, stream stays open

### mcp-core: OAuth device code flow
78. `POST /device/authorize` → returns device_code, user_code, interval
79. `POST /token` before approval → `authorization_pending`
80. `POST /token` after approval → access_token returned
81. `POST /token` after denial → `access_denied`
82. `POST /token` after 10 min → `expired_token`
83. `POST /token` with invalid device_code → error
84. Multiple pending device codes simultaneously → each tracked independently
85. Expired device codes pruned, no memory leak

### mcp-core: auth middleware
86. Request to `/{integration}` without Bearer → 401 with WWW-Authenticate header
87. Request with valid Bearer → passes through to MCP handler
88. Request with invalid/revoked Bearer → 401
89. OAuth endpoints accessible without Bearer
90. Token `last_used_at` updated on successful authenticated request

### mcp-core: ProviderRegistry
91. Provider enabled → path returns MCP tools
92. Provider disabled mid-session → next request returns 404
93. Provider re-enabled → path works again
94. `enabledPaths()` reflects current state
95. Unknown path → 404 regardless of registry state

### mcp-core: per-stream HTTP server lifecycle
96. Stream closes cleanly → Ktor server disposed, no resource leak
97. Stream closes mid-request → server handles gracefully
98. Multiple concurrent streams → independent HTTP servers, no cross-contamination

### Tunnel: mux frame parser
99. Valid DATA frame → payload routed to correct stream
100. Valid OPEN frame → new MuxStream emitted with correct sniHostname and generated sessionId UUID
101. Valid CLOSE frame → stream closed, app notified
102. Valid ERROR frame → TunnelError emitted with correct code and message
103. Frame with unknown type byte → logged, ignored
104. Incomplete frame (WebSocket message too short) → error, other streams unaffected
105. OPEN for stream ID that already exists → error logged, ignored

### Tunnel: mux outbound
106. App writes to MuxStream.output → framed as DATA with correct stream ID
107. MuxStream.close() → CLOSE frame sent
108. Multiple streams writing concurrently → frames interleaved correctly, no corruption

### Tunnel: TLS server accept
109. Valid TLS ClientHello → handshake completes, MuxStream emits plaintext
110. Invalid TLS from client → handshake fails, stream closed with ERROR(STREAM_REFUSED)
111. CertificateStore returns null cert → TunnelError.CertExpired, stream refused
112. Cert doesn't match private key → TLS fails, error emitted

### Tunnel: CertificateStore integration
113. Valid cert → mTLS to relay succeeds
114. Null cert → tunnel emits CertExpired, doesn't attempt mux
115. getCertExpiry() used by WorkManager for renewal timing
116. storeCertChain() after renewal → subsequent connections use new cert

### Tunnel: state machine
117. DISCONNECTED → connect() → CONNECTING → WebSocket opens → CONNECTED
118. CONNECTED → OPEN arrives → ACTIVE
119. ACTIVE → last stream closes → CONNECTED
120. CONNECTED → disconnect() → DISCONNECTING → CLOSE all → DISCONNECTED
121. ACTIVE → WebSocket drops → all streams torn down → DISCONNECTED
122. CONNECTING → handshake fails → DISCONNECTED + ConnectionFailed
123. CONNECTING → mTLS rejected → DISCONNECTED + AuthRejected

### Tunnel: FCM dispatch
124. FCM `type: "wake"` → TunnelService started, mux connection opened
125. FCM `type: "renew"` → cert renewal triggered, no mux connection
126. FCM unknown type → logged, ignored

### Tunnel: cert renewal execution
127. Renewal with valid mTLS → POST /renew, new cert received and stored
128. Renewal with expired cert → POST /renew with Firebase token + signature, new cert stored
129. Renewed cert CN/SAN mismatch → cert rejected, error logged
130. Relay returns rate_limited → retry scheduled for retry_after
131. Relay returns 5xx → exponential backoff retry
132. Network unavailable → WorkManager retry with network constraint
133. Renewal succeeds during active mux → new cert stored, current mux unaffected

### Tunnel: onboarding execution
134. Full onboarding: keypair → CSR → /register → cert + subdomain stored
135. Relay unreachable during onboarding → error surfaced to app
136. Rate limited during onboarding → rate_limited surfaced with retry_after
137. Partial failure (keypair generated, /register fails) → no partial state, clean retry

### :notifications — NotificationModel + audit
138. MuxConnected → ShowForeground
139. MuxDisconnected with tool calls + setting=Summary → PostSummary
140. MuxDisconnected with tool calls + setting=Each usage → individual PostToolUsage per call
141. MuxDisconnected with tool calls + setting=Suppress → no post-session notification
142. MuxDisconnected with zero tool calls → PostWarning("Roused with no usage")
143. StreamOpened → foreground notification updates stream count
144. StreamClosed (last stream) → DismissForeground
145. ErrorOccurred (stream-level) → silent, audit only
146. ErrorOccurred (connection-level) → PostError
147. Notification permission denied → post_session forced to Suppress, foreground still works
148. Audit entry persisted on tool call with all fields (timestamp, tool, args, result, duration, sessionId, providerId)
149. Audit query by sessionId → correct entries (notification deep-link target)
150. Audit query by date range + provider → filter works
151. Audit retention: >30 day entries pruned on launch
152. Audit empty state → no crash

### :work — service lifecycle, wakelock, WorkManager
153. FCM `wake` → foreground service starts → TunnelClient.connect() called
154. FCM `renew` → WorkManager renewal enqueued, no service start
155. Foreground service posts notification via createForegroundNotification()
156. TunnelState ACTIVE → wakelock acquired
157. TunnelState CONNECTED (from ACTIVE) → wakelock released
158. TunnelState CONNECTING → wakelock acquired
159. TunnelState DISCONNECTED → wakelock released, no leak
160. Rapid state transitions → wakelock balanced (no leak)
161. Idle timeout: CONNECTED for N minutes → disconnect() called
162. Idle timeout cancelled: stream opens before expiry
163. Idle timeout disabled when battery optimization exempt
164. Idle timeout cannot be disabled without exemption
165. WorkManager cert renewal: <14 days → POST /renew triggered
166. WorkManager cert renewal: cert not expiring → no-op
167. FCM token refresh → Firestore updated

### :api + :health — integration contract
168. HealthConnectIntegration.isAvailable() → true when Health Connect installed
169. HealthConnectIntegration.isEnabled() → true when permissions granted
170. Permissions denied → isEnabled() returns false
171. Permissions revoked externally → isEnabled() reflects change
172. OnboardingFlow completes → integration becomes enabled
173. OnboardingFlow cancelled → integration stays available (not enabled)

### :app — wiring, navigation, integration management
174. App launch after onboarding → main screen shown
175. App launch before onboarding → onboarding flow shown
176. Integration enabled → ProviderRegistry updated → path serves MCP
177. Integration disabled → ProviderRegistry updated → path returns 404
178. Device code approval: correct code → token issued
179. Device code approval: wrong code → error, retry
180. Device code denial → access_denied to client
181. Client revoked → token invalid immediately
182. Subdomain rotation → confirmation → new subdomain, tokens revoked, UI updated
183. Rotation within 30 days → rejected
184. Battery optimization card shown when not exempt, dismissable
185. Notification permission denied at onboarding → forced suppress setting

## Still Needs Design

1. **Third-party provider discovery** — bound service intent filter, verification, trust UI (future, not v1)
