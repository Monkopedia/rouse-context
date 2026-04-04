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

## SNI Parsing

When a raw TCP connection arrives on port 443:
1. Read enough bytes to parse the TLS ClientHello (buffer them)
2. Extract SNI hostname from the ClientHello
3. Strip subdomain from `{subdomain}.rousecontext.com`
4. Look up device in local cache (falls back to Firestore)
5. If device has active mux → assign stream ID, OPEN, forward buffered bytes
6. If device offline → fire FCM, hold connection, wait up to 20s (configurable)

The relay MUST NOT consume the ClientHello — it buffers and replays it to the device as the first DATA frame so the client↔device TLS handshake works.

## Relay Internal State

In-memory:
- `HashMap<subdomain, MuxConnection>` — active device WebSocket connections
- `HashMap<stream_id, ClientConnection>` — active client TCP sockets per device
- Device record cache with TTL (from Firestore)

Stream IDs are assigned per-mux-connection, incrementing u32 starting at 1. Reset when mux connection drops. Max concurrent streams per device: 8 (configurable).

## API Endpoints

All served over HTTPS on the relay's own domain (e.g. `api.rousecontext.com` or same host, different port).

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
Pre-flight wakeup.

Relay:
1. Check if device has active mux connection → if yes, return 200 immediately
2. If not, fire FCM, wait for device to connect (up to 20s)
3. Return 200 when connected, 504 on timeout

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
api_port = 8443
domain = "rousecontext.com"

[timeouts]
fcm_wakeup_secs = 20
websocket_ping_secs = 30
subdomain_reclaim_days = 180

max_streams_per_device = 8
subdomain_rotation_cooldown_days = 30

[acme]
ca_url = "https://acme.letsencrypt.org/directory"
```

Secrets via env vars: `CLOUDFLARE_API_TOKEN`, `FIREBASE_SERVICE_ACCOUNT_JSON`, `ADMIN_ALERT_WEBHOOK` (for rate limit notifications).

## Graceful Shutdown

On SIGTERM/SIGINT:
1. Stop accepting new client TCP connections and new mux WebSocket connections
2. Send CLOSE for every active stream on every mux connection
3. Wait for in-flight DATA frames to drain (5s timeout)
4. Close all mux WebSockets and client TCP connections
5. Log shutdown stats (sessions closed, mux connections dropped)
6. Exit

## Monitoring

### `/status` endpoint (API port, no auth)
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
