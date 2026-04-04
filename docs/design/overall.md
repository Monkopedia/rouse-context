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
- **Android Keystore keypair** — hardware-backed, private key never leaves secure element.
- **Subdomain** — assigned once at onboarding, tied to Firebase UID + public key.
- **Uninstall+reinstall = new device.** Old subdomain expires after TTL.
- **Re-registration** (same Firebase UID, e.g. app data cleared): requires both Firebase token AND signature with original private key. If either is lost, device gets new subdomain. UI should explain this clearly.

Auth methods by phase:

| Phase | Auth method |
|---|---|
| Onboarding (no cert) | Firebase ID token (JWT) |
| Normal operation | mTLS with device cert |
| Cert renewal (valid) | mTLS on mux connection |
| Cert renewal (expired) | Firebase token + private key signature over CSR |

## Firestore Data Model

```
devices/{subdomain}
  fcm_token: string
  firebase_uid: string
  public_key: string       // from original CSR, for verifying expired-cert renewals
  cert_expires: timestamp
  registered_at: timestamp
```

Security rules: device can only write its own document (matched by firebase_uid). Relay reads all via service account.

## End-to-End Flows

### Onboarding (first run)

1. App generates keypair in Android Keystore
2. App registers with Firebase anonymous auth → gets UID + FCM token
3. App creates CSR
4. App calls relay: `POST /register` with Firebase ID token + CSR + FCM token
5. Relay assigns random subdomain, performs DNS-01 ACME challenge via Cloudflare
6. Relay stores device record in Firestore
7. Relay returns cert + subdomain to device
8. Device stores cert, ready for mTLS

### Normal session (cold — device not connected)

1. MCP client connects to `abc123.rousecontext.com:443`
2. Relay parses SNI, looks up device (Firestore, cached), no active mux → fires FCM
3. Relay holds client TCP, buffers ClientHello, waits up to 20s
4. Device wakes, opens mux WebSocket with mTLS
5. Relay sends OPEN frame with SNI hostname
6. Relay forwards buffered ClientHello as DATA
7. Device demuxes, TLS handshake completes client↔device
8. MCP session active
9. On end: CLOSE frame, teardown

### Normal session (warm — device already connected)

Steps 4 skipped — relay immediately sends OPEN. No FCM, no wait.

### Wake pre-flight

1. Client calls `POST /wake/abc123`
2. Relay checks if device connected — if not, fires FCM
3. Returns 200 when device has mux connection (or timeout)
4. Client then opens TLS connection — gets instant splice

### Cert renewal (before expiry)

1. WorkManager daily check detects <30 days remaining
2. Device calls `POST /renew` with mTLS client cert auth
3. Relay performs ACME challenge, returns new cert

### Cert renewal (after expiry)

1. Device calls `POST /renew` with subdomain, new CSR, Firebase token, signature
2. Relay verifies Firebase UID match + signature against stored public key
3. Relay performs ACME, returns new cert
4. Device reconnects with new cert

## Integration Test Scenarios

### Onboarding
1. Happy path: fresh install → Firebase auth → CSR → cert received → registered in Firestore
2. Firestore unavailable → graceful failure, retry
3. ACME challenge failure → error returned, device retries
4. Re-registration (same Firebase UID + valid key signature) → same subdomain, new cert, old cert invalidated

### Normal sessions
5. Cold path: client → SNI → FCM → device wakes → mux → OPEN → TLS handshake → MCP tool call → response → CLOSE
6. Warm path: client → SNI → device already connected → OPEN → instant session
7. Concurrent clients: two clients to same device → two streams, both work independently
8. Client disconnects mid-session → relay sends CLOSE → device tears down McpSession
9. Device disconnects mid-session → relay closes client TCP

### Wake pre-flight
10. `/wake` for offline device → FCM sent → device connects → 200
11. `/wake` for already-connected device → immediate 200

### Edge cases
12. FCM timeout (device doesn't connect within 20s) → relay closes client connection
13. Device sends ERROR(STREAM_REFUSED) → relay closes client
14. Device sends DATA for unknown stream_id → relay sends ERROR(UNKNOWN_STREAM)
15. Mux WebSocket drops during active sessions → relay closes all client connections for that device
16. No mid-stream reconnect — clean teardown, client retries from scratch
17. Relay restart → all mux connections drop → devices reconnect on next FCM or client request

### Cert renewal
18. Normal renewal over mux → new cert issued
19. Expired cert renewal with valid Firebase + signature → new cert, subdomain preserved
20. Expired renewal with wrong Firebase UID → rejected
21. Expired renewal with invalid signature → rejected
22. ACME failure during renewal → error, device retries

### FCM token refresh
23. Token rotates → device updates Firestore → next FCM uses new token
24. Token rotates during active mux → Firestore updated, no session disruption

### Security
25. Mux connection without valid cert → TLS handshake fails
26. Mux with cert for different subdomain → maps to that subdomain, can't spoof another
27. `/register` with stolen Firebase token but different keypair → new subdomain, can't steal existing
28. Replayed old CSR to `/renew` → signature mismatch, rejected

## MCP Client Authentication (Device Code Flow)

OAuth 2.1 device authorization grant (RFC 8628). The device hosts the OAuth server inside the TLS tunnel — relay is not involved. Standard MCP clients discover auth via `/.well-known/oauth-authorization-server` per the MCP spec (2025-03-26).

### Flow

1. MCP client connects to `abc123.rousecontext.com` (triggers FCM wakeup if needed)
2. Client requests `GET /.well-known/oauth-authorization-server`
3. Device returns metadata: `device_authorization_endpoint`, `token_endpoint`, `grant_types_supported: ["urn:ietf:params:oauth:grant-type:device_code"]`
4. Client calls `POST /device/authorize`
5. Device generates device_code (opaque) + user_code (short, human-typeable, e.g. "ABCD-1234"), returns both + polling interval
6. Device shows notification: "A client wants to connect"
7. User opens app, enters user_code, taps Approve
8. Client polls `POST /token` with device_code until approved
9. Device returns access_token (long-lived, until revoked)
10. Client uses Bearer token for all subsequent MCP requests

### Token Management (on device)

- Tokens stored locally (Room or encrypted SharedPreferences)
- Each token has: client_id (from dynamic registration), created_at, last_used_at, optional label
- User can view and revoke tokens in app UI
- Token verification: device checks Bearer header on every MCP request before processing

### Where This Lives

`:mcp-core` — the HTTP routing (OAuth endpoints vs MCP protocol), token management, and auth verification. The tunnel provides the byte stream, mcp-core handles everything on top of it.

### Module Structure

`:mcp-core` and `:tunnel` will be **Kotlin Multiplatform** with `jvm` + `android` targets. Since both targets are JVM-based, SDK types from `commonMain` resolve to JVM. This enables `jvmTest` integration tests for the full OAuth + MCP flow without Android test runner overhead.

### Integration Test Scenarios (additions)

29. First connection — no token → 401 → OAuth discovery → device code flow → token issued → MCP session works
30. Subsequent connection — valid token → MCP session starts immediately, no auth prompt
31. Revoked token → 401 → client re-initiates auth flow
32. Expired device_code (user didn't approve in time) → client gets "expired_token" error, retries
33. Multiple authorized clients — each has own token, revoking one doesn't affect others

## Integration Model

Each `McpServerProvider` is exposed as a separate MCP server at its own URL path on the device's subdomain:

```
https://brave-falcon.rousecontext.com/health          → Health Connect
https://brave-falcon.rousecontext.com/notifications    → Notifications (future)
https://brave-falcon.rousecontext.com/contacts         → Contacts (future)
```

### How It Works

- All paths share one subdomain, one TLS cert, one mux connection
- The relay can't see paths (they're inside the TLS stream) — it just creates a new mux stream per client TCP connection, all routed by SNI
- The device's HTTP server (in `:mcp-core`) decrypts TLS, reads the request path, routes to the correct provider
- Each path is an independent MCP session from the client's perspective
- Multiple clients can connect to different integrations concurrently (separate mux streams)

### Auth

Per-device, not per-integration. One device code approval authorizes the client for all paths on that device. The user controls exposure by:
1. Which integration URLs they share with their MCP client
2. Enabling/disabling integrations in-app (hard kill switch)

### User Experience

Each integration has its own setup flow in the app:
1. User enables integration (e.g. "Health Connect")
2. App requests necessary permissions (Health Connect read access)
3. App shows the integration URL: `https://brave-falcon.rousecontext.com/health`
4. User adds this URL to their MCP client (e.g. Claude)
5. First connection triggers device code auth (if client not already authorized)
6. Integration is live

Integrations can be added incrementally — user doesn't need to set up everything at once. Each integration is independent except for the shared auth token.

### Path Routing in mcp-core

`:mcp-core` HTTP server handles:
- `/.well-known/oauth-authorization-server` → OAuth metadata (shared)
- `/device/authorize` → device code flow (shared)
- `/token` → token exchange (shared)
- `/{integration}/*` → routes to the corresponding `McpServerProvider`
- Unknown path → 404

### Integration Test Scenarios (additions)

34. Client connects to `/health` → routes to Health Connect provider, tools listed correctly
35. Client connects to `/notifications` → routes to Notifications provider (different tools)
36. Two clients connected to different integrations simultaneously → independent sessions
37. Integration disabled in-app → path returns 404 or appropriate error
38. Auth token works across all integration paths on same device

## Module Structure (revised)

Both `:mcp-core` and `:tunnel` will be **Kotlin Multiplatform** with `jvm` + `android` targets. Since both targets are JVM-based, all code (including MCP SDK types) is accessible from `commonMain`. This enables `jvmTest` integration tests for the full protocol stack without the Android test runner.

- `:mcp-core` (KMP: jvm + android) — MCP SDK, OAuth server (device code flow), token management, HTTP routing + path-based provider dispatch, `McpSession`, `McpServerProvider`
- `:tunnel` (KMP: jvm + android) — mux WebSocket client, framing, stream demux. `androidMain`: FCM, Keystore, WorkManager
- `:mcp-health` (Android library) — Health Connect integration, depends on `:mcp-core`
- `:app` (Android application) — wires everything, UI, foreground service, audit persistence, integration management
- `relay/` (Rust, Cargo) — independent, no Kotlin/Android deps

## Subdomain Format

Two-word combination: `{adjective}-{noun}.rousecontext.com` (e.g. `brave-falcon.rousecontext.com`).

- Word lists: ~2000 adjectives, ~2000 nouns → ~4M combinations
- Memorable, easy to type, easy to share verbally
- Collision avoidance: generate, check Firestore, retry if taken
- Subdomain is NOT a secret — security comes from device code auth + TLS, not subdomain obscurity
- Word lists ship with the relay binary (embedded at compile time)

### TTL / Reclamation

Abandoned subdomains (device stops renewing cert, no activity) can be reclaimed after a grace period. Suggested: **180 days** after cert expiry with no renewal attempt. The relay runs a periodic cleanup job.

## Still Needs Design

(none — all major design decisions are locked in)
