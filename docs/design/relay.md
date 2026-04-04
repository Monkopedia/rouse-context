# Relay Server Design

## Overview

Rust binary on a small VPS (512MB RAM). Handles:
- TLS passthrough for MCP client connections (SNI-routed)
- Mux WebSocket to devices (mTLS)
- FCM wakeup via REST API
- ACME cert issuance via Cloudflare DNS-01
- Device registration and cert renewal API

## Mux Framing Protocol

Transport: WebSocket binary frames over mTLS connection to device.

Frame: 5-byte header + payload

| Field | Size | Description |
|---|---|---|
| type | u8 | Message type |
| stream_id | u32 BE | Stream identifier (0 for connection-level) |
| payload | [u8] | Variable, depends on type |

| Type | Value | Direction | Payload |
|---|---|---|---|
| DATA | 0x01 | both | opaque stream bytes |
| OPEN | 0x02 | relay→device | SNI hostname (UTF-8) |
| CLOSE | 0x03 | both | empty |
| ERROR | 0x04 | both | error code (u8) + message (UTF-8) |

Error codes: UNKNOWN_STREAM (0x01), STREAM_REFUSED (0x02), TIMEOUT (0x03), INTERNAL (0x04)

WebSocket built-in ping/pong for keepalive (~30s). No application-level ping/pong.

## Port 443 — SNI-Based Routing

All traffic arrives on port 443. The relay peeks at the TLS ClientHello to extract the SNI hostname and routes accordingly:

- **SNI = `relay.rousecontext.com`** → terminate TLS (relay's own cert), serve HTTP. Routes: `/ws` (mux WebSocket), `/register`, `/renew`, `/wake/:subdomain`, `/status`.
- **SNI = `{subdomain}.rousecontext.com`** → passthrough mode. Buffer ClientHello, look up device, splice to mux stream.
- **SNI unknown** → close connection.

No separate API port. One port, two behaviors.

### TLS Configuration

- Relay's own cert for `relay.rousecontext.com`: manually provisioned via certbot, deployed from GitHub Secrets alongside the binary. Referenced in `relay.toml` (`tls.cert_path`, `tls.key_path`). Renewed via GitHub Actions scheduled workflow (weekly certbot run).
- Client cert: **optional** at TLS level (`rustls` `WebPkiClientVerifier` with `allow_unauthenticated()`). Individual endpoints enforce client cert requirements.
- Client cert trust anchors: Let's Encrypt root certs (ISRG Root X1, X2), embedded in binary. Future: add additional CA roots as fallback CAs are added.

### Client Passthrough (SNI = device subdomain)

1. Read enough bytes to parse TLS ClientHello (buffer them)
2. Extract subdomain from SNI
3. Look up device in local cache (falls back to Firestore)
4. If device has active mux → assign stream ID, OPEN, forward buffered ClientHello as DATA
5. If device offline → fire FCM (`type: "wake"`, `priority: "high"`), hold connection, wait up to 20s

The relay MUST NOT consume the ClientHello — it buffers and replays it so the client↔device TLS handshake works.

## Relay Internal State

In-memory:
- `HashMap<subdomain, MuxConnection>` — active device WebSocket connections
- `HashMap<stream_id, ClientConnection>` — active client TCP sockets per device
- Device record cache with TTL (from Firestore)

Stream IDs are u32, assigned per-mux-connection, incrementing from 1. Reset when mux connection drops. Max concurrent streams per device: 8 (configurable). Stream IDs are mux-level routing numbers only — the device generates a UUID per stream for audit trail purposes.

## API Endpoints

All served over HTTPS on `relay.rousecontext.com:443`. Optional mTLS — client cert accepted but not required at TLS level. Per-endpoint enforcement below.

### `GET /ws` → Mux WebSocket
**Requires valid client cert** (device cert, issued by trusted CA). Reject 401 if no cert or invalid cert. On valid cert: extract subdomain from CN/SAN, upgrade to WebSocket, begin mux framing.

### `POST /register`
Onboarding or subdomain rotation.

Request:
```json
{
  "firebase_token": "eyJ...",
  "csr": "base64-encoded-CSR",
  "fcm_token": "firebase-cloud-messaging-token",
  "signature": "base64-DER (required for re-registration or rotation)",
  "force_new": false
}
```

`signature` required when Firebase UID already has a subdomain. `force_new: true` for subdomain rotation (relay enforces 30-day cooldown).

Relay:
1. Verify Firebase ID token → extract UID
2. If UID already registered:
   a. Verify signature against stored public key
   b. If `force_new`: check 30-day cooldown, assign new subdomain, invalidate old
   c. If not `force_new`: reuse existing subdomain, issue new cert
3. If new UID: generate random two-word subdomain
4. Check ACME rate limit — if exceeded, store in `pending_certs/`, return `rate_limited` error, alert admin
5. Perform ACME DNS-01 challenge for `{subdomain}.rousecontext.com`
6. Store/update device record in Firestore
7. Return cert + subdomain

Response (success):
```json
{
  "subdomain": "brave-falcon",
  "cert": "base64-encoded-cert-chain",
  "relay_host": "relay.rousecontext.com"
}
```

Response (rate limited):
```json
{
  "error": "rate_limited",
  "retry_after_secs": 604800
}
```

### `POST /renew`
Cert renewal. Two auth paths depending on cert validity.

Request (expired cert — Firebase + signature):
```json
{
  "subdomain": "abc123",
  "firebase_token": "eyJ...",
  "csr": "base64-encoded-CSR",
  "signature": "base64-encoded-signature-over-CSR"
}
```

Request (valid cert — mTLS client cert auth):
```json
{
  "csr": "base64-encoded-CSR"
}
```
Subdomain extracted from client cert CN/SAN. No Firebase token needed.

Relay:
1. If mTLS: verify cert, extract subdomain
2. If Firebase: verify token → extract UID → verify against Firestore → verify signature against stored public key
3. Perform ACME DNS-01 challenge
4. Update cert_expires in Firestore
5. Return new cert

Response:
```json
{
  "cert": "base64-encoded-cert-chain"
}
```

### `POST /wake/:subdomain`
Pre-flight wakeup. No auth required but rate-limited.

Rate limit: 6 requests per subdomain per minute (in-memory token bucket). Returns 429 if exceeded.

Relay:
1. Check rate limit → if exceeded, return 429
2. Check if device has active mux connection → if yes, return 200 immediately
3. If not, fire FCM, wait for device to connect (up to 20s)
4. Return 200 when connected, 504 on timeout

## ACME Orchestration

### CA Selection
Primary: **Let's Encrypt** (50 certs per registered domain per week, no EAB needed). Future: add Google Trust Services or ZeroSSL as fallback CA.

### DNS-01 Challenge Flow
1. Relay generates ACME order for `{subdomain}.rousecontext.com`
2. CA returns challenge token
3. Relay creates TXT record `_acme-challenge.{subdomain}.rousecontext.com` via Cloudflare API
4. Relay waits for DNS propagation (poll ~5s interval, up to 60s)
5. Relay tells CA to validate
6. CA verifies TXT record, issues cert
7. Relay deletes TXT record via Cloudflare API (always, even on failure — no dangling records)
8. Relay returns cert to device

### Rate Limit Handling
- Let's Encrypt: 50 certs per registered domain (`rousecontext.com`) per week
- Relay tracks issued cert count per week
- When limit hit:
  1. Store blocked device in Firestore `pending_certs/{subdomain}`
  2. Return `rate_limited` error with `retry_after_secs`
  3. Send admin alert (email/webhook, configured in relay.toml)
- When quota resets: process pending queue, send FCM to each blocked device
- Cloudflare API: 1200 requests per 5 minutes (plenty)
- On ACME challenge failure: return error, device retries with exponential backoff
- On Cloudflare API failure: retry 3 times, then fail the request

### CAA Records

The production `rousecontext.com` DNS zone MUST have a CAA record restricting certificate issuance to the primary CA:

```
rousecontext.com. CAA 0 issue "letsencrypt.org"
```

This prevents other CAs from issuing certs for `*.rousecontext.com` even if credentials are compromised or an attacker controls a different CA's validation. If a fallback CA is added (e.g. Google Trust Services), add a second `issue` record for it.

Set via Cloudflare DNS alongside the zone configuration. Verify with `dig CAA rousecontext.com` after deployment.

### Credentials
- Cloudflare API token (scoped to DNS edit for `rousecontext.com` zone)
- Firebase service account JSON (for Firestore reads + FCM sends)

All via env vars. NOT in the repo.

## FCM Integration

Simple REST API call:
```
POST https://fcm.googleapis.com/v1/projects/{project_id}/messages:send
Authorization: Bearer {oauth2_token}

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

OAuth2 token obtained from Firebase service account using JWT grant. Cache token until near expiry.

### FCM Message Types

| Type | Priority | When |
|---|---|---|
| `wake` | high | Client connected, device needs to open mux immediately |
| `renew` | normal | Cert expiring within 7 days, device should renew soon |

## Daily Maintenance Job

Single `tokio::spawn` loop, runs once per day. Three tasks:

1. **Cert expiry nudge**: Query Firestore for devices where `cert_expires < now + 7 days AND cert_expires > now AND renewal_nudge_sent IS NULL`. Send FCM `type: "renew"` to each. Set `renewal_nudge_sent` timestamp.
2. **Pending cert queue**: Check if ACME rate limit has reset. Process any `pending_certs/` entries — issue certs, send FCM `type: "wake"` to notify devices.
3. **Subdomain reclamation**: Delete `devices/` entries where `cert_expires < now - 180 days` and no renewal attempts.

The `renewal_nudge_sent` field is cleared when a device successfully renews (in the `/renew` handler).

## Rust Crate Dependencies (expected)

- `tokio` — async runtime
- `tokio-tungstenite` — WebSocket
- `tokio-rustls` / `rustls` — TLS for mTLS termination on mux
- `reqwest` — HTTP client for Firestore, FCM, ACME
- `serde` / `serde_json` — serialization
- `instant-acme` or custom — ACME client
- `clap` — CLI arg parsing
- `tracing` — structured logging

## Subdomain Generation

Two-word combination: `{adjective}-{noun}` (e.g. `brave-falcon`). Word lists (~2000 each) embedded in the relay binary at compile time.

Generation: pick random adjective + noun, check Firestore for collision, retry if taken. With ~4M combinations and a small user base, collisions are rare.

## Config

TOML config file (`relay.toml`) with env var overrides for secrets:

```toml
[server]
listen_port = 443
domain = "rousecontext.com"
relay_hostname = "relay.rousecontext.com"

[tls]
cert_path = "/etc/rouse-relay/tls/cert.pem"
key_path = "/etc/rouse-relay/tls/key.pem"

[timeouts]
fcm_wakeup_secs = 20
websocket_ping_secs = 30
subdomain_reclaim_days = 180
max_streams_per_device = 8
subdomain_rotation_cooldown_days = 30

[acme]
ca_url = "https://acme.letsencrypt.org/directory"
```

Secrets via env vars: `CLOUDFLARE_API_TOKEN`, `FIREBASE_SERVICE_ACCOUNT_JSON`, `ADMIN_ALERT_WEBHOOK`.

### Deployment

VPS is stateless — all state is in Firestore. Deploy pipeline (GitHub Actions):
1. Build relay binary (cross-compile for target arch)
2. Write relay cert from GitHub Secrets
3. Deploy binary + cert + relay.toml to VPS via SSH/SCP
4. Restart relay service (systemd)

Relay cert for `relay.rousecontext.com` renewed by a separate GitHub Actions scheduled workflow (weekly certbot with Cloudflare DNS plugin), stored in GitHub Secrets.

## Graceful Shutdown

On SIGTERM/SIGINT:
1. Stop accepting new client TCP connections and new mux WebSocket connections
2. Send CLOSE for every active stream on every mux connection
3. Wait for in-flight DATA frames to drain (5s timeout)
4. Close all mux WebSockets and client TCP connections
5. Log shutdown stats (sessions closed, mux connections dropped)
6. Exit

## Monitoring

### `GET /status` (no auth)
```json
{
  "uptime_secs": 86400,
  "active_mux_connections": 12,
  "active_streams": 3,
  "total_sessions_served": 1847,
  "pending_fcm_wakeups": 1
}
```

### Structured logging (`tracing` crate, JSON format)
- ERROR: ACME failures, Firestore unreachable, FCM send failures
- WARN: FCM timeout, mux dropped with active streams
- INFO: new mux connection, stream opened/closed, cert issued/renewed, device registered
- DEBUG: individual DATA frames, cache hits/misses, ACME challenge steps

Tagged with `subdomain` and `stream_id` where applicable.

## Still Needs Design

(none -- all items resolved)
