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
| DATA | 0x00 | both | opaque stream bytes |
| OPEN | 0x01 | relay→device | SNI hostname (UTF-8) |
| CLOSE | 0x02 | both | empty |
| ERROR | 0x03 | both | error code (u32 BE) + message (UTF-8) |
| PING | 0x04 | both | 8-byte BE nonce on stream_id 0 |
| PONG | 0x05 | both | 8-byte BE nonce on stream_id 0 |

Error codes (u32 BE): `STREAM_REFUSED`, `STREAM_RESET`, `PROTOCOL_ERROR`, `INTERNAL_ERROR`.

Application-level PING/PONG on stream id 0 carries an 8-byte big-endian nonce so endpoints can detect a half-open WebSocket without relying on the WebSocket-layer ping (which some intermediaries forward without the peer process being alive). See `docs/design/relay-api.md` ("Mux Binary Framing Protocol") for the canonical wire format.

## Port 443 — SNI-Based Routing

All traffic arrives on port 443. The relay peeks at the TLS ClientHello to extract the SNI hostname and routes accordingly:

- **SNI = `relay.rousecontext.com`** → terminate TLS (relay's own cert), serve HTTP. The full endpoint catalog (`/ws`, `/register`, `/register/certs`, `/request-subdomain`, `/rotate-secret`, `/renew`, `/status`) is documented in `docs/design/relay-api.md`. There is no public `/wake` endpoint; FCM wakeup is internal to the SNI passthrough path on cache miss (see "Client Passthrough" below).
- **SNI = `{subdomain}.rousecontext.com`** → passthrough mode. Buffer ClientHello, look up device, splice to mux stream.
- **SNI unknown** → close connection.

No separate API port. One port, two behaviors.

### TLS Configuration

- Relay's own cert for `relay.rousecontext.com`: manually provisioned via certbot, deployed from GitHub Secrets alongside the binary. Referenced in `relay.toml` (`tls.cert_path`, `tls.key_path`). Renewed via GitHub Actions scheduled workflow (weekly certbot run).
- Client cert: **optional** at TLS level (`rustls` `WebPkiClientVerifier` with `allow_unauthenticated()`). Individual endpoints enforce client cert requirements.
- Client cert trust anchors: the **relay's private device CA** (the same CA that issues the `client_cert` returned from `/register/certs` and `/renew`). Loaded from `tls.ca_cert_path` in `relay.toml` at startup, not embedded in the binary. Public CAs (GTS, Let's Encrypt, ...) are not involved in mTLS — they only issue the device-facing `server_cert`.

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

All served over HTTPS on `relay.rousecontext.com:443`. Optional mTLS at the TLS layer — client cert accepted but not required; per-endpoint enforcement is documented in `docs/design/relay-api.md`. The endpoint catalog at the time of writing:

| Endpoint | Purpose | Auth |
|---|---|---|
| `POST /request-subdomain` | Round 0 of onboarding: reserve a single-word subdomain keyed by Firebase UID (short-TTL reservation). | Firebase ID token |
| `POST /register` | Round 1: consume the reservation, persist the device record, return the assigned subdomain plus per-integration secrets. | Firebase ID token (+ signature for re-registration / `force_new`) |
| `POST /register/certs` | Round 2: ACME DNS-01 → return server cert + relay-CA-issued client cert. Split out of `/register` to keep the registration round idempotent and quick. | Firebase ID token + signature over CSR |
| `POST /rotate-secret` | Replace the per-integration secret set wholesale (request body is authoritative; ids absent from the body are dropped). | mTLS client cert |
| `POST /renew` | Cert renewal. Path A (valid cert): mTLS client cert auth. Path B (expired cert): Firebase + signature. **Both paths require `subdomain`, `csr`, and `signature` in the body** — even Path A. mTLS only re-confirms identity; the body is authoritative. | mTLS or Firebase + signature |
| `GET /ws` | Mux WebSocket upgrade. | mTLS client cert |
| `GET /status` | Health and rough metrics. | none |

`docs/design/relay-api.md` is the canonical source for request/response shapes, error codes, signature semantics, and Firestore document schemas. This document only covers the architectural shape and the relay-internal behavior that does not surface as an endpoint.

### Internal FCM wakeup (no `/wake` endpoint)

There is no public `/wake/:subdomain` endpoint. FCM wakeup is triggered internally on the SNI passthrough fast-path when a client connects for a subdomain whose device has no active mux connection: the relay holds the inbound TCP/TLS connection, fires an FCM `wake` push, and waits for the device to dial in (up to ~20s). See "Client Passthrough" above.

## ACME Orchestration

### CA Selection
Primary: **Google Trust Services** (GTS) production directory `https://dv.acme-v02.api.pki.goog/directory`. GTS requires External Account Binding (RFC 8555 §7.3.4) on the first `newAccount` registration; EAB credentials are provisioned once via `gcloud publicca external-account-keys create` and supplied via the env vars named in `acme.external_account_binding_*_env`. GTS was chosen over Let's Encrypt because its issuance quota (tens of thousands of certs per day per account) is not a practical constraint for fresh-install churn or mass-install scenarios, whereas LE's 50 certs-per-registered-domain-per-week ceiling was. Let's Encrypt is still a supported fallback for self-hosters and can be selected by setting `acme.directory_url` (see `docs/self-hosting.md`).

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
- GTS (current production): ~100 new orders per hour per account; overall daily issuance quota is in the tens of thousands. In practice this is not a ceiling we hit during normal operation or mass onboarding.
- Let's Encrypt (fallback option for self-hosters): 50 certs per registered domain per week. This was the motivating reason to switch the default to GTS.
- The relay still tracks issued-cert count and handles CA-side rate-limit errors generically:
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
rousecontext.com. CAA 0 issue "pki.goog"
```

This prevents other CAs from issuing certs for `*.rousecontext.com` even if credentials are compromised or an attacker controls a different CA's validation. Self-hosted deployments using Let's Encrypt should publish `0 issue "letsencrypt.org"` instead (or in addition, if both CAs are configured). The startup CAA check (see `relay/src/caa_check.rs`, #322) verifies the configured ACME provider's identifier appears in the CAA record set at boot and logs ERROR on mismatch — a silent CAA misconfiguration would otherwise only surface at the next renewal, 60-90 days later.

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
      "type": "wake"
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
# Defaults to Google Trust Services production when omitted.
# directory_url = "https://dv.acme-v02.api.pki.goog/directory"
external_account_binding_kid_env = "GTS_EAB_KID"
external_account_binding_hmac_env = "GTS_EAB_HMAC"
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
