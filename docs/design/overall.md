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
| Onboarding `/request-subdomain` and `/register` | Firebase ID token (JWT) |
| Onboarding `/register/certs` (re-registration) | Firebase token + SHA256withECDSA signature over the firebase_token bytes (`relay/src/api/register.rs:67-93,144-152`) |
| Normal operation | mTLS with device cert |
| Cert renewal `POST /renew` (valid cert) | mTLS + body `{subdomain, csr, signature}`; signature over CSR DER (`relay/src/api/renew.rs:30-40,70-78`) |
| Cert renewal `POST /renew` (expired cert) | Firebase token + body `{subdomain, csr, signature}`; same signature semantics (signature over CSR DER) |

## Firestore Data Model

```
devices/{subdomain}
  fcm_token: string
  firebase_uid: string
  public_key: string                    // Base64 DER SubjectPublicKeyInfo, from CSR submitted at /register/certs
  cert_expires: timestamp
  registered_at: timestamp
  last_rotation: timestamp              // for enforcing 30-day subdomain rotation cooldown
  renewal_nudge_sent: timestamp | null  // set when 7-day FCM sent, cleared on successful renewal
  secret_prefix: string | null          // legacy single-secret prefix; deprecated, kept for back-compat
  valid_secrets: [string]               // authoritative SNI fast-path list of {adjective}-{integrationId} first-labels
  integration_secrets: { [integration_id: string]: string }
                                        // id → secret map, source of truth for rotate-secret merge

pending_certs/{subdomain}               // devices blocked on ACME rate limits
  fcm_token: string
  csr: string
  blocked_at: timestamp
  retry_after: timestamp

subdomain_reservations/{subdomain}      // short-TTL reservations created by /request-subdomain
  fqdn: string                          // base domain included
  firebase_uid: string                  // UID that reserved the name
  expires_at: timestamp                 // returned to the pool after this
  base_domain: string                   // for the release valve
  created_at: timestamp
```

The `subdomain_reservations` collection is the bridge between `/request-subdomain` and `/register`: the reservation is created in step 1 and consumed (single-use) in step 2. Expired reservations are swept by the maintenance loop (`relay/src/firestore.rs:65-82`).

Security rules: device can only write its own `devices/` document (matched by firebase_uid for FCM token updates). Relay reads/writes all via service account.

## End-to-End Flows

### Onboarding (first run)

Three relay hops, sequenced from `OnboardingFlow.execute()` (`core/tunnel/src/jvmMain/kotlin/com/rousecontext/tunnel/OnboardingFlow.kt:53-80`). The CSR is generated *after* subdomain assignment so its SAN can include the actual FQDN.

1. App registers with Firebase anonymous auth → gets UID + FCM token.
2. App calls relay: `POST /request-subdomain` with the Firebase ID token. Relay picks a free name, writes a short-TTL `subdomain_reservations/{name}` document keyed to the UID, and returns the reserved subdomain.
3. App calls relay: `POST /register` with Firebase ID token + FCM token + integration ids. Relay verifies the reservation, deletes it (single-use), creates `devices/{subdomain}`, and returns `{subdomain, relay_host, secrets}` (the per-integration `{adjective}-{integrationId}` secrets map). No CSR yet.
4. App persists the assigned subdomain and integration secrets locally.
5. App generates an ECDSA P-256 keypair in Android Keystore and a PKCS#10 CSR whose CN/SAN match `{subdomain}.rousecontext.com`.
6. App calls relay: `POST /register/certs` with Firebase token + CSR (+ signature on re-registration). Relay performs the DNS-01 ACME challenge via Cloudflare, mints the relay-CA client certificate, and returns `{server_cert, client_cert, relay_ca_cert, relay_host}`.
7. Device stores both PEMs in app-private storage; the `:work` foreground service can now bring up mTLS to the relay.

This three-hop split was introduced by #389 so a fresh install is never left in the half-configured "subdomain but no certs" state where AI-client TLS handshakes EOF and mux mTLS reconnects 401. The decision to fire all three hops at the Continue tap on NotificationPreferences (rather than deferring certs to first-integration-add) is documented in [`docs/ux-decisions.md`](../ux-decisions.md) under 2026-04-24.

Failure semantics:
- `/request-subdomain` failure → no local state written; the reservation (if any) expires on its own.
- `/register` failure after a successful reservation → reservation TTL expires, next attempt picks a new name.
- `/register/certs` failure after `/register` succeeds → subdomain + integration secrets are *kept* so the user can retry just the cert hop without re-burning a subdomain reservation (#163).

### Normal session (cold — device not connected)

1. MCP client connects to a per-integration host such as `brave-health.abc123.rousecontext.com:443`. The first label is `{adjective}-{integrationId}`, drawn from the device's `valid_secrets` set; the second label is the device subdomain (`relay/src/api/register.rs:61-65`, `app/.../UrlBuilder.kt`).
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

### Cert renewal

Two-stage proactive renewal:

1. **14 days to expiry**: WorkManager daily check triggers renewal via `POST /renew` (mTLS auth). Silent unless it fails.
2. **7 days to expiry**: If first renewal failed, relay sends FCM push prompting immediate renewal. Warning notification to user.
3. **Expired**: Device cannot mTLS. Renews via `POST /renew` with Firebase token + signature. Error notification: "Certificate expired, renewing..." Incoming client connections fail until renewed.

Both renewal paths are body-driven (`relay/src/api/renew.rs:30-40,70-78`) — the request body always carries `{subdomain, csr, signature}`, and `signature` is over the CSR DER. The two paths differ only in *what additionally* authenticates the request:

Renewal flow (valid cert):
1. Device calls `POST /renew` over mTLS with body `{subdomain, csr, signature}`. The mTLS handshake re-confirms identity; the body is still authoritative for which subdomain to renew.
2. Relay verifies signature against stored public key, performs ACME challenge, returns new cert.

Renewal flow (expired cert):
1. Device cannot mTLS, so it calls `POST /renew` with body `{subdomain, csr, signature, firebase_token}`.
2. Relay verifies Firebase UID matches the device record + signature against stored public key.
3. Relay performs ACME challenge, returns new cert.
4. Device stores new cert, reconnects.

### Security monitoring

Two complementary mechanisms provide a strong, user-verifiable trust story:

**Self-cert verification**
The app periodically connects to its own relay subdomain (`{subdomain}.rousecontext.com`) and verifies the TLS leaf cert fingerprint matches the cert it provisioned. Uses a custom `X509TrustManager` that checks the SHA-256 fingerprint of the leaf cert's public key against the fingerprint stored alongside the private key in the Android Keystore.

During the 90-day renewal window, the app stores both the current cert fingerprint and the pending renewal cert fingerprint, accepting either as valid to avoid false positives during legitimate rotation.

If the fingerprint doesn't match, the app surfaces an alert immediately. This detects relay compromise or targeted MITM where someone swaps the cert at the relay level.

**Certificate Transparency log monitoring**
The app periodically queries CT logs (via `crt.sh/?q={subdomain}.rousecontext.com&output=json`) for any cert ever issued against its subdomain. Cross-references against the cert the app provisioned. If CT shows any cert for this subdomain that the app didn't issue, surfaces an immediate alert.

This defeats targeted interception attacks where an adversary filters the self-check traffic but MITMs actual AI client sessions — they can't hide a fraudulent cert from the CT logs.

**Scheduling**
- Both checks run on first app launch after onboarding, then every few hours via WorkManager
- Failed checks degrade to a visible warning state (never silently swallowed)
- Results stored in the local audit log alongside tool call history

**CAA records**
The production `rousecontext.com` DNS zone should have a CAA record restricting issuance to a single CA. The upstream `rousecontext.com` zone uses Google Trust Services, so it publishes `0 issue "pki.goog"`; self-hosters using Let's Encrypt would use `0 issue "letsencrypt.org"` instead. This prevents other CAs from issuing for the domain even if credentials are compromised. Document in deployment runbook.

### Subdomain rotation

1. User taps "Generate new address" in Settings
2. App shows confirmation: "All connected clients will lose access. You'll need to re-add the new URL. You can only do this once every 30 days."
3. App calls `POST /register` with `"force_new": true` + Firebase token + signature
4. Relay verifies identity, assigns new subdomain, performs ACME, invalidates old subdomain
5. All client tokens revoked
6. App shows new subdomain + URLs

### FCM payload format

Two `type` values ship today. Wake (passthrough cold-start, high priority):

```json
{
  "message": {
    "token": "{device_fcm_token}",
    "android": { "priority": "high" },
    "data": {
      "type": "wake"
    }
  }
}
```

Renewal nudge (sent by the relay maintenance loop when a device is inside the 7-day expiry window and `renewal_nudge_sent` is unset):

```json
{
  "message": {
    "token": "{device_fcm_token}",
    "android": { "priority": "normal" },
    "data": {
      "type": "renew"
    }
  }
}
```

Data-only message (no `notification` block) so `FirebaseMessagingService` always receives it. `type` is extensible — the `:work` FCM dispatcher routes `wake` to the foreground tunnel service and `renew` to the WorkManager renewal job; unknown types are logged and ignored.

## MCP Client Authentication (Device Code Flow)

OAuth 2.1 device authorization grant (RFC 8628). **Per-integration auth** — each integration is an independent MCP server with its own OAuth endpoints, device codes, and tokens. The device hosts OAuth inside the TLS tunnel — relay is not involved.

### Flow

Each integration ships at its own per-integration hostname `{adjective}-{integrationId}.{subdomain}.rousecontext.com`. The integration is identified by the SNI label, not by a URL path segment, so OAuth + MCP endpoints all live at the *root* of that host.

1. MCP client connects to `brave-health.abc123.rousecontext.com` (triggers FCM wakeup on cold start).
2. Client requests `GET /.well-known/oauth-authorization-server`.
3. Device returns metadata for the Health Connect integration (the SNI tells `:core:mcp` which provider to dispatch to).
4. Client calls `POST /device/authorize`.
5. Device generates device_code (opaque, 32 bytes base64url) + user_code (8 chars alphanumeric uppercase, no 0/O/1/I/L, displayed as `XXXX-XXXX`), returns both + polling interval (5s).
6. Device shows notification: "Claude wants to access **Health Connect**".
7. User opens app, enters user_code, taps Approve.
8. Client polls `POST /token` with device_code every 5s until approved.
9. Device returns access_token (opaque, 32 bytes base64url, no expiry — valid until revoked).
10. Client uses Bearer token for all subsequent MCP requests to `/mcp` on that hostname.

A client authorized for the Health host has NO access to the Notifications host. Each integration requires its own auth flow.

### Device code TTL: 10 minutes

Polling responses: `authorization_pending` (keep polling), `slow_down` (increase interval), `expired_token` (10 min elapsed), `access_denied` (user denied).

### Token Management (on device)

- Tokens stored locally (Room database)
- Each token: integration_id, client_id, access_token_hash, created_at, last_used_at, label
- Tokens scoped to integration — a token issued on the Health host can't access the Notifications host
- User can view and revoke tokens per-integration in app UI
- Token verification: device checks Bearer header on every request, scoped to the SNI-derived integration id
- All tokens revoked on subdomain rotation

### Where This Lives

`:core:mcp` — HTTP routing, per-integration OAuth endpoints, token management, auth verification. The tunnel provides the byte stream (plaintext after TLS termination) along with the SNI hostname, and `:core:mcp` handles everything on top of it.

## :core:mcp HTTP Server

The tunnel hands `MuxStream` (plaintext `InputStream`/`OutputStream` after inner TLS termination, plus the SNI hostname from the OPEN frame) to the app. The app passes it to `:core:mcp`, which runs a Ktor HTTP server over the stream.

### Protocol

**Streamable HTTP** per MCP spec (2025-03-26). Client sends HTTP POST requests, server responds with JSON. SSE for server-initiated messages if needed. This is the standard remote MCP transport — stock MCP clients support it natively.

### Stack on device

```
MuxStream (raw bytes from relay) + SNI hostname
  → TLS server accept (tunnel, using device server cert)
  → plaintext InputStream/OutputStream
  → Ktor embedded HTTP server (:core:mcp)
  → resolve integration id from SNI first label ({adjective}-{integrationId})
  → route within that integration:
      /.well-known/oauth-authorization-server → that integration's OAuth metadata
      /device/authorize → that integration's device code flow
      /token → that integration's token exchange
      /mcp/* → that integration's MCP Streamable HTTP (auth required)
      unknown path → 404
  → unknown integration / disabled → 404 (also triggers an audit notification)
```

### Auth per integration

Each per-integration host is a self-contained MCP server with its own OAuth. The SNI label resolves the integration; each integration handler checks its own Bearer token. Returns 401 with `WWW-Authenticate` pointing to that host's `/.well-known/oauth-authorization-server` if the token is missing or invalid.

### `:core:mcp` Interfaces

```kotlin
/** Live registry of enabled integrations. App implements, :core:mcp queries per-request. */
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

Example for the Health integration on subdomain `abc123` with secret `brave-health`:
```json
{
  "issuer": "https://brave-health.abc123.rousecontext.com",
  "device_authorization_endpoint": "https://brave-health.abc123.rousecontext.com/device/authorize",
  "token_endpoint": "https://brave-health.abc123.rousecontext.com/token",
  "grant_types_supported": ["urn:ietf:params:oauth:grant-type:device_code"],
  "response_types_supported": [],
  "code_challenge_methods_supported": []
}
```

Each integration has its own issuer and endpoints, all rooted at its per-integration hostname.

## Integration Model

Each `McpServerProvider` is exposed as a separate MCP server at its own per-integration hostname under the device subdomain (`{adjective}-{integrationId}.{subdomain}.rousecontext.com`). The integration is identified by the SNI label, not by a URL path segment.

```
https://brave-health.abc123.rousecontext.com         → Health Connect
https://crisp-notifications.abc123.rousecontext.com  → Notifications (future)
https://swift-contacts.abc123.rousecontext.com       → Contacts (future)
```

The `{adjective}` portion of the first label is rotated independently of the `{integrationId}` so the routing key is unguessable to bots scanning the subdomain (#92). See `docs/design/relay-api.md` for the per-integration secret API surface.

### How It Works

- One subdomain hosts many per-integration hostnames; each gets its own SAN entry on the device's relay-CA-issued client cert (via `valid_secrets`), but a single mux connection serves them all.
- Relay routes by SNI: it looks up the device by the second label (`abc123`), checks the first label against `valid_secrets`, then forwards.
- Inside the TLS tunnel the device's HTTP server (`:core:mcp`) reads the SNI hostname from the OPEN frame, resolves the integration, then routes by URL path within that integration.
- Each per-integration host is an independent MCP session from the client's perspective.
- Max 8 concurrent streams per device (configurable on relay).

### Auth

**Per-integration.** Each integration is an independent MCP server with its own OAuth flow, device codes, and tokens. A client authorized for the Health host has no access to the Notifications host.

User controls exposure by:
1. Which integration URLs they share with their MCP client
2. Enabling/disabling integrations in-app (disabled integration returns 404)
3. Revoking tokens per-integration in the authorized clients UI

### Routing inside `:core:mcp`

After the SNI label resolves the integration, paths under that hostname route to:

- `/.well-known/oauth-authorization-server` → that integration's OAuth metadata
- `/device/authorize` → that integration's device code flow
- `/token` → that integration's token exchange
- `/mcp/*` → that integration's MCP Streamable HTTP (auth required)
- Unknown path on a known integration → 404
- Unknown SNI label or disabled integration → 404, also triggers app notification (audit trail)

### User Experience

1. User enables integration (e.g. "Health Connect") in app
2. App requests necessary permissions
3. App shows the integration URL with copy button / share sheet
4. User adds URL to their MCP client
5. First connection triggers device code auth (if not already authorized)
6. Integration is live

## ACME Rate Limit Handling

### Primary CA: Google Trust Services (GTS)

`rousecontext.com` production runs against GTS's `dv.acme-v02.api.pki.goog` directory. GTS quotas (roughly 100 new orders/hour/account, tens of thousands of certs/day overall) are far above what the relay generates even under mass-onboarding load — quota was the main reason to move off Let's Encrypt, whose 50-cert-per-registered-domain-per-week ceiling was the binding constraint for fresh-install churn. GTS requires External Account Binding on the first `newAccount` call; see `docs/design/relay.md` for the config knobs.

Let's Encrypt remains a supported option for self-hosters (see `docs/self-hosting.md`). The rate-limit handling below is generic and applies to any ACME CA the relay is pointed at.

### When limit is hit:
1. Relay returns `{"error": "rate_limited", "retry_after_secs": 604800}` to device
2. Relay stores blocked device in `pending_certs/{subdomain}` with FCM token
3. Relay sends automated admin alert (email/webhook, configured in relay.toml)
4. Device shows notification: "Certificate issuance temporarily delayed. Will retry automatically on [date]."
5. Device schedules WorkManager retry for `retry_after` time

### When quota resets:
1. Relay processes pending queue
2. Relay sends FCM to each blocked device to trigger cert pickup

## Module Structure

`:core:mcp` and `:core:tunnel` are configured as Kotlin Multiplatform modules but in practice ship a single JVM target — `core/tunnel/src/` and `core/mcp/src/` both contain only `jvmMain/` + `jvmTest/` (no `commonMain`, no `androidMain`). The Android-specific glue (FCM, Keystore wiring, WorkManager) lives in `:work` and `:app`. The full module layout per `settings.gradle.kts`:

- `:app` — Compose UI, foreground service host, navigation, Koin wiring, integration management screens. The only module that depends on all others.
- `:core:mcp` — MCP SDK, OAuth server (device code flow), token management, HTTP routing, `McpSession`, `McpServerProvider`. Pure JVM.
- `:core:tunnel` — mux WebSocket client, mux protocol framing, TLS server accept, `CertificateStore` interface, `OnboardingFlow`, `CertProvisioningFlow`. Pure JVM. Must not know about MCP.
- `:core:bridge` — wiring layer that connects tunnel streams to `:core:mcp` sessions.
- `:core:testfixtures` — shared fakes used by tests in other modules.
- `:api` — `McpIntegration` interface, `IntegrationStateStore`. Contract for integration modules.
- `:integrations` — Health Connect and other on-device data sources, depends on `:api` and `:core:mcp`.
- `:notifications` — notification state machine, audit persistence (Room). Depends on `:core:tunnel` and `:core:mcp`.
- `:work` — foreground service, FCM receiver, WorkManager renewal job, wakelock. Depends on `:core:tunnel` and `:notifications`.
- `:device-tests` — instrumented tests that need a real device.
- `:e2e` — end-to-end Gradle integration test (`:e2e:e2eTest`) against a connected device.
- `relay/` — Rust, Cargo. Independent of the Android/JVM tree, no Kotlin deps.

## Subdomain Format

Post-#92, the primary pool is **single-word**: a name is picked from the union of the adjective list and the noun list (`relay/src/api/request_subdomain.rs:10-12`, `relay/src/subdomain.rs:36-44`). So a typical assigned device subdomain looks like `falcon.rousecontext.com` or `brave.rousecontext.com`.

When the single-word pool is exhausted (or a collision retry burns through too many candidates), the generator falls back to the legacy `{adjective}-{noun}` form, e.g. `brave-falcon.rousecontext.com`.

- Word lists: ~2000 adjectives + ~2000 nouns → ~4000 single-word names primary, ~4M two-word combinations as overflow
- Lowercase alphanumeric + hyphen only (DNS-safe)
- Collision avoidance: generate, check Firestore + active reservations, retry if taken
- NOT a secret — security comes from the per-integration `{adjective}-{integrationId}` SNI label, device-code auth, and TLS

The per-integration first label is what's hard to guess: examples elsewhere in this doc that show full per-integration URLs like `brave-health.abc123.rousecontext.com` use the single-word `abc123`-style subdomain to make the layout obvious, but the actual shape is whatever the generator returns for that device.

### TTL / Reclamation

Abandoned subdomains reclaimed **180 days** after cert expiry with no renewal attempt. Relay periodic cleanup job.

### Rotation

User can request new subdomain once per 30 days. Old subdomain invalidated immediately. All client tokens revoked.

## Test coverage

The repository is exercised by:

- `core/tunnel/src/jvmTest/` — tunnel integration tests (mux protocol, end-to-end session, cold-start, onboarding/cert provisioning, OAuth-over-tunnel)
- `core/mcp/src/jvmTest/` — `:core:mcp` units (HTTP routing, OAuth device-code flow, token store, session lifecycle)
- `core/bridge/src/jvmTest/` — bridge wiring tests
- `app/src/test/` — Android-side ViewModel, navigation, audit, and screenshot tests
- `notifications/src/test/`, `work/src/test/`, `integrations/src/test/`, `api/src/test/` — module-specific units
- `device-tests/src/test/` and `:device-tests` instrumented suite — anything that needs a real device
- `e2e/` — `:e2e:e2eTest` end-to-end against a connected device
- `relay/tests/` — Rust integration tests for the relay

See those module test directories for the authoritative list of scenarios. Per-feature coverage notes belong with the feature, not in this design doc — keeping them here led to systematic drift.

## Still Needs Design

1. **Third-party provider discovery** — bound service intent filter, verification, trust UI (future, not v1)
