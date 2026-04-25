# Relay Server API Specification

Complete API reference for the Rouse Context relay server (`relay.rousecontext.com`).

This document describes the *shipped* HTTP/WebSocket surface and the mux binary
framing protocol carried over the device WebSocket. It is the canonical
contract between the device (`:core:tunnel` / `:work`) and the relay
(`relay/`). Architectural prose lives in `relay.md`; the device side is
detailed in `tunnel-client.md`.

## Conventions

### Base URL

All REST endpoints are served over HTTPS at `https://relay.rousecontext.com`
(port 443). The relay terminates TLS for the **relay's own hostname only**.
Per-device subdomain traffic (e.g. `brave-health.abc123.rousecontext.com`) is
**pure TLS passthrough** routed by SNI and is **not** an HTTP endpoint — see
`relay.md` for passthrough behavior. There is no `/wake` HTTP endpoint; FCM
wakeup happens internally inside the passthrough resolver
(`relay/src/passthrough.rs::resolve_device_stream`) when a client TCP connection
arrives for an offline device.

### Endpoint summary

| Method | Path | Auth | Notes |
|---|---|---|---|
| `POST` | `/register` | Firebase ID token (+ signature on re-register) | Round 1 of onboarding. Assigns subdomain + integration secrets. No CSR yet. |
| `POST` | `/register/certs` | Firebase ID token (+ signature in PoP path) | Round 2 of onboarding. Submits CSR; returns server cert + client cert + relay CA. |
| `POST` | `/request-subdomain` | Firebase ID token | Optional pre-flight: reserve a subdomain before `/register`. Idempotent retry. |
| `POST` | `/renew` | Body-driven signature; optional Firebase token (Path B) | Renew certs. Subdomain in body, not in TLS cert. |
| `POST` | `/rotate-secret` | mTLS client certificate (required) | Replace per-integration secrets wholesale. Subdomain extracted from cert. |
| `GET`  | `/ws` | mTLS client certificate (required) | Mux WebSocket upgrade. Subdomain extracted from cert. |
| `GET`  | `/status` | None | Operational metrics. |

A separate `--test-mode` admin server (loopback only, opt-in) exposes the
`/test/*` routes — see [Admin / Test-Mode Endpoints](#admin--test-mode-endpoints).

### Authentication mechanisms

Three authentication mechanisms appear across endpoints. Each endpoint section
states which one(s) it requires.

| Method | Mechanism | When used |
|---|---|---|
| **Firebase ID token** | `firebase_token` field in the JSON body. Verified via Google's public keys (RS256 JWT). The `sub` claim is taken as the Firebase UID. | `/register`, `/register/certs`, `/request-subdomain`, `/renew` (Path B). |
| **Body-driven signature** | `signature` field in the JSON body, ECDSA P-256 over a per-endpoint payload, verified against the device's stored `public_key`. The exact payload differs by endpoint — see each section. | `/register` re-registration, `/register/certs` PoP path, `/renew` (always required). |
| **mTLS client certificate** | TLS client certificate presented during the TLS handshake. Verified against the relay's private device CA (the same CA that issued `client_cert` in `/register/certs` / `/renew`). The subdomain is extracted from the certificate's CN or first SAN `dNSName` matching `*.{base_domain}`, with the suffix stripped. | `/rotate-secret`, `/ws`. |

`require_device_identity` is an axum middleware (`relay/src/api/mod.rs:211`)
that rejects requests without a valid `DeviceIdentity` extension before the
handler runs. Routes that require mTLS sit behind that middleware; everything
else is on the public router and does its own (body-driven) auth check.

The TLS client certificate is **optional** at the TLS layer
(`allow_unauthenticated()` in the relay's rustls config). Endpoints that
require mTLS enforce it at the application layer and return 401 if no valid
certificate was presented.

### Common error response envelope

All non-2xx responses from REST endpoints use this JSON envelope:

```json
{
  "error": "<error_code>",
  "message": "<human-readable description>",
  "retry_after_secs": 604800
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `error` | string | always | Machine-readable error code (see table below). |
| `message` | string | always | Human-readable description, suitable for logging but not for end-user display. |
| `retry_after_secs` | u64 | only for `rate_limited`, `cooldown`, `acme_rate_limited` | Seconds the client should wait before retrying. |

### Error codes

The constructors on `ApiError` (`relay/src/api/mod.rs:30-148`) define the full
set:

| Code | HTTP | Constructor | Description |
|---|---|---|---|
| `bad_request` | 400 | `ApiError::bad_request` | Malformed body, missing required field, invalid value. |
| `unauthorized` | 401 | `ApiError::unauthorized` | Missing or invalid credentials. |
| `forbidden` | 403 | `ApiError::forbidden` | Valid credentials, insufficient permission (e.g. UID mismatch, signature verification failure). |
| `not_found` | 404 | `ApiError::not_found` | Subdomain (or other named resource) not in Firestore. |
| `rate_limited` | 429 | `ApiError::rate_limited` | Generic per-key rate limit (e.g. `/request-subdomain` per-UID limiter). |
| `cooldown` | 429 | `ApiError::cooldown` | Subdomain rotation requested before the cooldown window expired. |
| `acme_rate_limited` | 429 | `ApiError::acme_rate_limited` | ACME CA rate limit hit. |
| `acme_failure` | 502 | `ApiError::acme_failure` | ACME challenge or issuance failed (DNS, CA, etc.). |
| `timeout` | 504 | `ApiError::timeout` | Internal wakeup or upstream timed out. |
| `internal` | 500 | `ApiError::internal` | Unexpected server error. |

### Content type

All request and response bodies are `application/json` unless otherwise noted.
`/ws` is a WebSocket upgrade and afterwards carries binary mux frames.

---

## Endpoints

### `POST /register` — Round 1: device registration

Onboards a device's Firebase identity and returns a subdomain assignment plus
per-integration secrets. **No CSR is submitted at this stage** — the device
generates its keypair and CSR after seeing the assigned subdomain, then calls
`POST /register/certs`.

#### Authentication

- **Firebase ID token** in the body is required.
- **Signature** (`signature` field) is required when the Firebase UID already
  has a registered subdomain — i.e. for re-registration or rotation. The
  signature payload for `/register` is the **raw bytes of the
  `firebase_token` string**, not the CSR DER. (The CSR-DER signature is what
  `/register/certs` and `/renew` use; do not confuse the two.)

#### Request body

`relay/src/api/register.rs::RegisterRequest`:

```json
{
  "firebase_token": "eyJhbGciOiJSUzI1NiIs...",
  "fcm_token": "dGVzdF9mY21fdG9rZW4...",
  "signature": "MEUCIQD...",
  "force_new": false,
  "integrations": ["health", "outreach", "notifications"]
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `firebase_token` | string | always | Firebase ID token JWT for the project. |
| `fcm_token` | string | always | Current FCM registration token for the device. |
| `signature` | string | conditional | Base64 DER ECDSA signature over `firebase_token.as_bytes()`. Required iff the UID already has a subdomain. |
| `force_new` | bool | optional (default `false`) | When `true`, request a new subdomain (rotation). Subject to the rotation cooldown. |
| `integrations` | array of string | optional (default `[]`) | Integration ids the device wants secrets minted for (e.g. `health`, `outreach`). One `{adjective}-{integrationId}` secret is generated per entry. |

#### Success response — 200 OK

`relay/src/api/register.rs::RegisterResponse`:

```json
{
  "subdomain": "abc123",
  "relay_host": "relay.rousecontext.com",
  "secrets": {
    "health": "brave-health",
    "outreach": "swift-outreach",
    "notifications": "calm-notifications"
  }
}
```

| Field | Type | Description |
|---|---|---|
| `subdomain` | string | Assigned device subdomain (left-most label only). The full FQDN is `{subdomain}.{base_domain}`. |
| `relay_host` | string | The relay's own hostname (clients use this for `/renew`, `/rotate-secret`, etc.). |
| `secrets` | object (string → string) | Map of integration id → generated `{adjective}-{integration_id}` secret. Empty / omitted when `integrations` was empty. |

`secrets` is **omitted from the JSON** when the map is empty (`#[serde(skip_serializing_if = "HashMap::is_empty")]`).

#### Error responses

| Status | Condition | Code | Example message |
|---|---|---|---|
| 400 | Empty `firebase_token` or `fcm_token`. | `bad_request` | `"Missing required field: firebase_token"` |
| 401 | Firebase token invalid, expired, or wrong project. | `unauthorized` | `"Invalid Firebase ID token: ..."` |
| 403 | UID has an existing subdomain and `signature` is missing. | `forbidden` | `"Signature required for re-registration"` |
| 403 | Signature does not verify against the stored public key. | `forbidden` | `"Signature verification failed: ..."` |
| 429 | `force_new: true` but the subdomain rotation cooldown is still active. `retry_after_secs` carries the remaining cooldown. | `cooldown` | `"Subdomain rotation cooldown not expired"` |
| 500 | Firestore lookup or write failed. | `internal` | `"Firestore lookup failed: ..."` |

#### Behavior

`relay/src/api/register.rs::handle_register`:

1. Validate `firebase_token` and `fcm_token` are non-empty.
2. Verify the Firebase token; extract `uid`.
3. Look up any existing device by `firebase_uid`.
4. **Existing UID, `force_new == false`** (re-registration): require
   `signature`; verify it against the stored public key over the bytes of
   `firebase_token`. Reuse the existing subdomain.
5. **Existing UID, `force_new == true`** (rotation): require and verify
   `signature` (same payload as above). Enforce the subdomain rotation
   cooldown (`limits.subdomain_rotation_cooldown_days`, default 30). Best-effort
   delete the old DNS records and Firestore document, then generate a new
   subdomain.
6. **New UID**: if there is a non-expired `subdomain_reservations/` entry for
   this UID (created by `/request-subdomain`), consume it. Otherwise, generate
   a fresh subdomain via the in-process generator.
7. Generate one `{adjective}-{integration_id}` secret per requested integration
   id. Persist both the integration → secret map (`integration_secrets`) and
   the flat `valid_secrets` list used by the SNI fast path. Seed the
   in-memory SNI cache so the next passthrough connection hits the new
   secrets without waiting for Firestore eventual consistency.
8. Write `devices/{subdomain}` with `public_key = ""` and
   `cert_expires = UNIX_EPOCH` — these are populated in round 2.
9. Return `subdomain`, `relay_host`, `secrets`.

The device must follow up with `POST /register/certs` to obtain certificates.

---

### `POST /register/certs` — Round 2: certificate issuance

Submits the device's CSR and returns the server cert (ACME-issued), client
cert (relay CA), and the relay CA root cert.

#### Authentication

- **Firebase ID token** is required.
- **Signature** is required iff `devices/{subdomain}.public_key` is already
  set — i.e. the device record has previously bound a key (reissuance / cert
  re-roll on the same record). The signature payload here is the **raw DER
  bytes of the CSR** (`relay/src/api/register.rs:367`). On a fresh record the
  signature is ignored if present (round 2 is the step that binds the key for
  the first time).

This proof-of-possession check (issue #201) prevents an attacker who only
holds a valid Firebase token from re-issuing a relay-CA client cert keyed to
their own keypair. Use `/renew` for routine renewals on an existing key.

#### Request body

`relay/src/api/register.rs::CertRequest`:

```json
{
  "firebase_token": "eyJhbGciOiJSUzI1NiIs...",
  "csr": "MIIBIjANBgkqhki...",
  "signature": "MEUCIQD..."
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `firebase_token` | string | always | Firebase ID token JWT. |
| `csr` | string | always | Base64-encoded DER PKCS#10 CSR with FQDN `{subdomain}.{base_domain}`, signed with an ECDSA P-256 key. |
| `signature` | string | conditional | Base64 DER ECDSA signature over the **raw CSR DER bytes** using the device's registered private key. Required iff the device record already has a `public_key`. |

#### Success response — 200 OK

`relay/src/api/register.rs::CertResponse`:

```json
{
  "subdomain": "abc123",
  "server_cert": "-----BEGIN CERTIFICATE-----\n...",
  "client_cert": "-----BEGIN CERTIFICATE-----\n...",
  "relay_ca_cert": "-----BEGIN CERTIFICATE-----\n...",
  "relay_host": "relay.rousecontext.com"
}
```

| Field | Type | Description |
|---|---|---|
| `subdomain` | string | The device's subdomain (echoed for convenience). |
| `server_cert` | string | PEM-encoded ACME-issued server certificate (serverAuth EKU, wildcard SAN `*.{subdomain}.{base_domain}`). Issued by the configured ACME provider — Google Trust Services in production, Let's Encrypt or another public CA for self-hosted deployments. |
| `client_cert` | string | PEM-encoded relay-CA client certificate (clientAuth EKU, CN/SAN = `{subdomain}.{base_domain}`). Used for mTLS on `/ws`, `/rotate-secret`, etc. |
| `relay_ca_cert` | string | PEM-encoded relay device-CA root, so the device can pin the issuer when validating future `client_cert` rolls. |
| `relay_host` | string | The relay's own hostname (echo of the server config). |

#### Error responses

| Status | Condition | Code | Example message |
|---|---|---|---|
| 400 | Missing `firebase_token` or `csr`; bad Base64; CSR not parseable. | `bad_request` | `"Failed to parse CSR: ..."` |
| 401 | Firebase token invalid. | `unauthorized` | `"Invalid Firebase ID token: ..."` |
| 403 | Record has a registered key but `signature` is missing. | `forbidden` | `"Signature required: device already has a registered key. ..."` |
| 403 | Signature does not verify against the stored public key. | `forbidden` | `"Signature verification failed: ..."` |
| 404 | No `devices/{subdomain}` for this UID — caller must do `/register` first. | `not_found` | `"No device registered for this UID. Call POST /register first."` |
| 429 | ACME CA rate limit hit. `retry_after_secs` is the CA's hint. | `acme_rate_limited` | `"Certificate issuance rate limited"` |
| 502 | ACME DNS-01 challenge failed (DNS propagation, CA rejection, Cloudflare API failure). | `acme_failure` | `"ACME DNS-01 challenge failed: ..."` |
| 500 | Firestore failure, device CA misconfiguration, internal CA signing failure. | `internal` | `"ACME error: ..."` / `"Failed to sign client cert: ..."` |

#### Behavior

`relay/src/api/register.rs::handle_register_certs`:

1. Validate `firebase_token` and `csr`; Base64-decode the CSR.
2. Verify the Firebase token; extract `uid`; look up the device record by UID.
3. If `record.public_key` is non-empty, verify `signature` over the CSR DER
   against the stored public key (PoP check, #201).
4. Extract the public key from the CSR (this also validates the CSR's
   self-signature).
5. Issue the ACME server cert against the CSR's public key, with FQDN
   `*.{subdomain}.{base_domain}` (wildcard, so per-integration sub-subdomains
   share one cert).
6. Sign a relay-CA client cert with the same public key; CN/SAN are
   `{subdomain}.{base_domain}`.
7. Update `devices/{subdomain}.public_key` and bump
   `cert_expires` to `now + 90d`.
8. Return both certs and the relay CA root.

---

### `POST /request-subdomain` — Reserve a subdomain before `/register`

Optional pre-flight call used by `OnboardingFlow` so the device can show the
chosen subdomain in the UI before the user accepts permissions and continues.

#### Authentication

- **Firebase ID token** is required.
- Per-Firebase-UID rate limit (`request_subdomain_rate_limiter`), keyed by
  `uid`. Defaults: burst 6, refill one token every 10 s
  (`limits.request_subdomain_rate_burst` / `request_subdomain_rate_refill_secs`,
  configurable in `relay.toml`). Pool-enumeration defence.

#### Request body

`relay/src/api/request_subdomain.rs::RequestSubdomainRequest`:

```json
{
  "firebase_token": "eyJhbGciOiJSUzI1NiIs..."
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `firebase_token` | string | always | Firebase ID token JWT. |

#### Success response — 200 OK

```json
{
  "subdomain": "ivory",
  "base_domain": "rousecontext.com",
  "fqdn": "ivory.rousecontext.com",
  "reservation_ttl_seconds": 600
}
```

| Field | Type | Description |
|---|---|---|
| `subdomain` | string | Reserved subdomain label. |
| `base_domain` | string | Base domain the reservation lives under. Currently always the primary base domain; the release-valve is in place for future density-weighted picks (#92). |
| `fqdn` | string | Full host = `{subdomain}.{base_domain}`. |
| `reservation_ttl_seconds` | u64 | Seconds remaining before the reservation expires. On a fresh pick this is `limits.subdomain_reservation_ttl_secs` (default 600); on an idempotent retry (UID already holds a reservation) it is the remaining time on the existing entry. |

#### Idempotent retry

If the UID already holds a non-expired `subdomain_reservations/` entry, the
handler returns it instead of picking a new one — calling
`/request-subdomain` repeatedly during onboarding does not burn pool entries
or rotate the user's name out from under them.

#### Pool selection

`select_free_subdomain` in `relay/src/api/request_subdomain.rs`:

1. Tier 1 — single-word pool (adjectives ∪ nouns): two attempts.
2. Tier 2 — `{adjective}-{noun}` overflow: up to five attempts.
3. If all attempts collide (extremely unlikely with a ~54k pool), return
   `internal`.

A name is *free* if there is no `devices/` document and no non-expired
`subdomain_reservations/` entry under it. Expired reservations count as free.

#### Error responses

| Status | Condition | Code |
|---|---|---|
| 400 | Empty `firebase_token`. | `bad_request` |
| 401 | Invalid Firebase token. | `unauthorized` |
| 429 | Per-UID rate limit exhausted. `retry_after_secs` = seconds until next token. | `rate_limited` |
| 500 | Firestore lookup, pool-exhaustion, or persistence failure. | `internal` |

The reservation is consumed by the next successful `POST /register` from the
same UID. Expired reservations are swept opportunistically by both
`/request-subdomain` (when the same UID retries) and the maintenance loop.

---

### `POST /renew` — Certificate renewal

Renew the device's certificates. Both auth paths are **driven by the JSON
body**, not the TLS client certificate of the HTTP connection — the relay
does not inspect the cert here.

#### Authentication

- **Signature** is **always required**. It signs the **raw CSR DER bytes**
  with the device's registered private key
  (`relay/src/api/renew.rs:71-78,217`). This is *not* the same payload that
  `/register` re-registration signs — that one signs the firebase token bytes.
- **Path A** (valid cert / "mTLS-equivalent"): body has `csr`, `subdomain`,
  `signature`. No `firebase_token`. The signature alone proves control of the
  registered key.
- **Path B** (expired cert): body has `csr`, `subdomain`, `signature`, plus
  `firebase_token`. Firebase re-authenticates the user while the signature
  still proves key control. The relay matches `claims.uid` against
  `devices/{subdomain}.firebase_uid`.

The relay detects Path B by the presence of `firebase_token`; otherwise it
takes Path A.

#### Request body

`relay/src/api/renew.rs::RenewRequest`:

```json
{
  "subdomain": "abc123",
  "csr": "MIIBIjANBgkqhki...",
  "signature": "MEUCIQD...",
  "firebase_token": "eyJhbGciOiJSUzI1NiIs..."
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `subdomain` | string | always | Device subdomain (must match an existing `devices/` document). |
| `csr` | string | always | Base64-encoded DER PKCS#10 CSR, ECDSA P-256. |
| `signature` | string | always | Base64 DER ECDSA signature over the **raw CSR DER bytes** using the registered private key. |
| `firebase_token` | string | Path B only | Firebase ID token JWT. Presence selects Path B. |

#### Success response — 200 OK

`relay/src/api/renew.rs::RenewResponse`:

```json
{
  "server_cert": "-----BEGIN CERTIFICATE-----\n...",
  "client_cert": "-----BEGIN CERTIFICATE-----\n...",
  "relay_ca_cert": "-----BEGIN CERTIFICATE-----\n..."
}
```

The fields mirror `/register/certs` (server cert, client cert, relay-CA root)
so the device reuses the same cert-storage path for first-time issuance and
renewal. `subdomain` and `relay_host` are not echoed because the device
already supplied the subdomain.

After a successful renewal the relay also sets
`devices/{subdomain}.cert_expires = now + 90d` and clears any
`renewal_nudge_sent` timestamp.

#### Error responses

| Status | Condition | Code |
|---|---|---|
| 400 | Missing `csr`, `subdomain`, or `signature`; bad Base64; CSR unparseable. | `bad_request` |
| 401 | Path B: invalid Firebase token. | `unauthorized` |
| 403 | Path B: Firebase UID does not match `devices/{subdomain}.firebase_uid`. | `forbidden` |
| 403 | Signature does not verify against `devices/{subdomain}.public_key`. | `forbidden` |
| 404 | No `devices/{subdomain}` document. | `not_found` |
| 429 | ACME CA rate limit. | `acme_rate_limited` |
| 502 | ACME DNS-01 failure. | `acme_failure` |
| 500 | Firestore or device-CA failure. | `internal` |

---

### `POST /rotate-secret` — Replace per-integration secrets

Authoritative replace-by-request-set rotation of the per-integration secrets
that gate the SNI fast path. See issues #148 (introduction), #202 (mTLS
hardening), and #285 (replace-wholesale semantics).

#### Authentication

- **mTLS client certificate is required** — enforced by the
  `require_device_identity` middleware (`relay/src/api/mod.rs:211`). The
  subdomain is taken from the cert, **not** from the request body. The body
  carries no `subdomain` field and there is no fallback path: a request
  without an mTLS identity returns 401.
- This is the only auth check. Rotation does not require a Firebase token or
  a body signature.

#### Request body

`relay/src/api/rotate_secret.rs::RotateSecretRequest`:

```json
{
  "integrations": ["health", "outreach"]
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `integrations` | array of string | always | Authoritative list of integration ids the device wants live. May legitimately be empty. |

The list is the **authoritative set**:

- For each id in the request that is already in
  `record.integration_secrets`, the existing secret is **preserved**
  (idempotent re-rotates do not invalidate live URLs).
- For each id in the request that is not yet in the stored map, a fresh
  `{adjective}-{integration_id}` secret is minted.
- For each id in the stored map but **not** in the request, the entry is
  **dropped** (the per-integration URL becomes invalid immediately).
- An empty list legitimately wipes every secret. The device stays onboarded
  (subdomain, account, certs untouched); only the SNI fast path is emptied.

#### Success response — 200 OK

`relay/src/api/rotate_secret.rs::RotateSecretResponse`:

```json
{
  "success": true,
  "secrets": {
    "health": "brave-health",
    "outreach": "calm-outreach"
  }
}
```

| Field | Type | Description |
|---|---|---|
| `success` | bool | Always `true` on a 200 response. |
| `secrets` | object (string → string) | Full integration → secret mapping after replace-wholesale. The client mirrors this wholesale into its local `IntegrationSecretStore`. |

#### Error responses

| Status | Condition | Code |
|---|---|---|
| 401 | No mTLS client certificate, or cert lacks a usable subdomain. | `unauthorized` |
| 404 | No `devices/{subdomain}` document. | `not_found` |
| 500 | Firestore lookup or write failure. | `internal` |

#### Behavior

`relay/src/api/rotate_secret.rs::handle_rotate_secret`:

1. Read the subdomain from the request's `DeviceIdentity` extension; reject
   with 401 if absent.
2. Load `devices/{subdomain}` (404 if missing).
3. Compute the replace-wholesale set as described above. Rebuild
   `valid_secrets` from the resulting map so the SNI cache stays in sync.
4. Persist the new `integration_secrets` and `valid_secrets`. Update the
   in-memory SNI cache (an empty result evicts the cache entry entirely).
5. Return the full mapping.

The relay logs the diff (`requested`, `newly_generated`, `removed`) at
`info` level.

---

### `GET /ws` — Mux WebSocket upgrade

Establishes the multiplexed WebSocket from device to relay. All MCP-client
traffic for this device is splice-routed through this connection using the
binary mux framing protocol (see [Mux Binary Framing
Protocol](#mux-binary-framing-protocol)).

#### Authentication

**mTLS required.** The device must present a valid TLS client certificate
issued by the relay's private device CA (the `client_cert` returned from
`/register/certs` or `/renew`). The relay extracts the subdomain from the
certificate's CN or first SAN `dNSName` matching the configured base domain.
The `require_device_identity` middleware rejects requests with no
`DeviceIdentity` extension before the handler runs.

#### Request

```
GET /ws HTTP/1.1
Host: relay.rousecontext.com
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Version: 13
Sec-WebSocket-Key: <base64-encoded-key>
```

No request body, no query parameters, no additional headers beyond standard
WebSocket upgrade.

#### Success response

HTTP 101 Switching Protocols. The connection is upgraded to a WebSocket
carrying binary mux frames.

#### Error responses

| Status | Condition | Code |
|---|---|---|
| 401 | No client certificate, invalid/expired/untrusted certificate, or no subdomain extractable from the cert. | `unauthorized` |
| 403 | Device certificate is valid but no `devices/{subdomain}` exists in Firestore. | `forbidden` |
| 500 | Firestore lookup failure. | `internal` |

In test fixtures with `limits.allow_ws_auto_create_device = true` the relay
auto-creates a placeholder Firestore record for an authenticated subdomain
that has never registered. Production sets this to `false` (default) so a
revoked cert cannot silently resurrect a subdomain (#209).

#### Behavior after upgrade

`relay/src/api/ws.rs::handle_mux_session`:

1. Register the WebSocket as the active mux session for the subdomain in
   `SessionRegistry`. Any prior session for the same subdomain is replaced;
   the older session's read/write loop terminates when its WebSocket peer
   closes.
2. The write loop sends a **WebSocket-level** `Ping` every
   `ws_ping_interval_secs` (default 30 s).
3. The read loop applies a `ws_read_timeout_secs` deadline per message: if no
   message — including pongs to the relay's WebSocket pings — arrives within
   the deadline, the session is treated as dead and torn down.
4. Binary frames are decoded as mux frames (see below) and dispatched to
   `MuxSession::dispatch_incoming`.
5. Text frames are control-plane messages. The only currently supported type
   is `{"type":"fcm_token","token":"..."}` which updates the device's
   `fcm_token` in Firestore (so a relay restart can still wake it).
6. `PING` mux frames from the device are answered immediately with a `PONG`
   carrying the same nonce. The relay does not currently originate `PING`
   mux frames itself; PONGs received from the device are ignored. (Half-open
   detection is driven by the device side — see `relay.md` and #179.)
7. On WebSocket close (from either side, normal or abrupt), all active
   streams for this device are torn down, the corresponding client TCP
   connections are closed, and the session is unregistered.

---

### `GET /status` — Health and metrics

Operational metrics. No auth.

#### Success response — 200 OK

`relay/src/api/status.rs::StatusResponse`:

```json
{
  "uptime_secs": 86400,
  "active_mux_connections": 12,
  "active_streams": 3,
  "total_sessions_served": 1847,
  "pending_fcm_wakeups": 1
}
```

| Field | Type | Description |
|---|---|---|
| `uptime_secs` | u64 | Seconds since the relay process started. |
| `active_mux_connections` | u32 | Number of devices with an open mux WebSocket. |
| `active_streams` | u32 | Total active mux streams across all devices (each = one in-flight client TCP splice). |
| `total_sessions_served` | u64 | Cumulative count of mux sessions ended (read on session teardown). |
| `pending_fcm_wakeups` | u32 | In-flight passthrough waits for a device that has been FCM-pinged but has not yet connected back. |

---

## Mux Binary Framing Protocol

Transport: binary WebSocket frames over the mTLS connection established via
`GET /ws`. One mux frame per WebSocket binary message — no fragmentation or
reassembly at the mux layer.

The wire format and semantics MUST be kept in sync with:

- `relay/src/mux/frame.rs` (relay)
- `core/tunnel/src/jvmMain/kotlin/com/rousecontext/tunnel/MuxFrame.kt` (device)
- `core/tunnel/src/jvmMain/kotlin/com/rousecontext/tunnel/MuxCodec.kt` (encoder/decoder)

### Frame layout

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|     type      |                  stream_id                    |
+-+-+-+-+-+-+-+-+                                               |
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                    payload (variable length)                  |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

| Offset | Size | Field | Encoding |
|---|---|---|---|
| 0 | 1 byte | `type` | unsigned 8-bit. |
| 1 | 4 bytes | `stream_id` | unsigned 32-bit, big-endian. |
| 5 | variable | `payload` | depends on `type`. |

**Minimum frame size:** 5 bytes (header only). A WebSocket binary message
shorter than 5 bytes MUST be treated as a protocol error.

### Frame types

| Hex | Name | Direction | `stream_id` | Payload |
|---|---|---|---|---|
| `0x00` | `DATA` | both | non-zero | Opaque stream bytes (TLS ciphertext). |
| `0x01` | `OPEN` | relay → device | non-zero (relay-assigned) | UTF-8 SNI hostname. |
| `0x02` | `CLOSE` | both | non-zero | Empty. |
| `0x03` | `ERROR` | both | per-stream (non-zero) or 0 (connection-level) | u32-BE error code + optional UTF-8 message. |
| `0x04` | `PING` | device → relay (currently) | MUST be 0 | u64-BE nonce (8 bytes). |
| `0x05` | `PONG` | relay → device (currently) | MUST be 0 | u64-BE nonce echoed from `PING` (8 bytes). |

`PING` / `PONG` are an application-layer keepalive on top of the WebSocket's
own ping/pong, added for half-open detection (#179) — Doze and NAT-rebind
scenarios where OkHttp's WebSocket pings fail to surface a dead socket. The
device originates pings, the relay echoes them. Either peer may initiate in
principle (the codecs are symmetric); current production traffic only flows
device → relay.

#### `DATA` (0x00)

Opaque stream payload. The relay MUST NOT interpret the bytes — this is TLS
ciphertext from the client ↔ device session.

**Flow:** Each byte read from the client TCP socket is sent (possibly
batched) as a `DATA` frame. Each `DATA` frame received from the device is
written to the corresponding client TCP socket. The relay/device may batch
or split TCP reads into `DATA` frames of any size. Empty `DATA` frames
SHOULD NOT be sent but MUST be tolerated.

#### `OPEN` (0x01)

Signals the device to accept a new incoming client connection.

| Field | Value |
|---|---|
| `stream_id` | Newly assigned, monotonically increasing from 1 per mux connection. Never 0. |
| `payload` | UTF-8 SNI hostname from the client's TLS ClientHello, no null terminator (e.g. `brave-health.abc123.rousecontext.com`). |

**Relay flow:**

1. Assign the next available stream id.
2. Send `OPEN` with the SNI hostname.
3. Immediately send the buffered TLS ClientHello bytes as a `DATA` frame on
   the same `stream_id`.
4. Splice forward in both directions.

**Device flow:** create a `MuxStream` for the new id; the first `DATA`
frame(s) carry the ClientHello, which the device's `TlsAcceptor` consumes
to terminate TLS. The device-side mux deserializer keeps `OPEN` payload
bytes around (they are still on the wire) but does not currently route by
hostname — the routing decision is made by the SNI hostname extracted from
the ClientHello itself, on the device side.

If the device cannot accept the stream (e.g. `max_streams_per_device` hit),
it responds with `ERROR(STREAM_REFUSED)` for that `stream_id`.

#### `CLOSE` (0x02)

Clean teardown of a stream. Full close, not half-close.

| Field | Value |
|---|---|
| `stream_id` | The stream to close. Non-zero. |
| `payload` | Empty. Receiver MUST ignore any bytes. |

After `CLOSE`, no further `DATA` frames should be sent for that
`stream_id`; receivers MUST silently discard them. Sending `CLOSE` for an
already-closed stream is harmless. `CLOSE` for an unknown stream is logged
at debug level and ignored (race with peer-initiated close).

- Relay receives `CLOSE` from device → close the corresponding client TCP
  socket (both halves).
- Client TCP socket closes → relay sends `CLOSE` for that stream id.

#### `ERROR` (0x03)

Reports an error condition. May be per-stream (`stream_id != 0`) or
connection-level (`stream_id == 0`).

| Field | Value |
|---|---|
| `stream_id` | Per-stream id, or 0 for connection-level errors. |
| `payload` | 4-byte u32-BE error code, optionally followed by a UTF-8 message. |

**Payload layout:**

| Offset | Size | Field |
|---|---|---|
| 0 | 4 bytes | `error_code` (u32 big-endian) |
| 4 | variable | UTF-8 `message` (may be empty) |

**Error codes** (`relay/src/mux/frame.rs::ErrorCode`,
`MuxFrame.kt::MuxErrorCode`):

| Code | Name | Meaning |
|---|---|---|
| 1 | `STREAM_REFUSED` | Receiver cannot accept the stream (e.g. `max_streams_per_device` reached). Typical reply to an `OPEN` the device cannot fulfil. |
| 2 | `STREAM_RESET` | Stream is being abnormally torn down on this side; treat as a non-clean `CLOSE`. |
| 3 | `PROTOCOL_ERROR` | Frame violated the mux contract (bad payload length, invalid `stream_id`, etc.). |
| 4 | `INTERNAL_ERROR` | Unspecified internal error. |

On `ERROR` for a specific stream, the receiver MUST close that stream and
the corresponding client TCP socket (if on the relay side). On `ERROR` with
`stream_id == 0`, the receiver SHOULD tear down the entire mux connection.

### Stream id 0

`stream_id == 0` is reserved for connection-level signaling:

- Connection-level `ERROR` frames.
- `PING` / `PONG` (always on `stream_id == 0`).

`DATA`, `OPEN`, `CLOSE` with `stream_id == 0` are invalid and MUST be
ignored (log at WARN).

### Stream lifecycle

```
relay assigns stream_id
        |
        v
  OPEN (relay -> device)  [payload: SNI hostname]
        |
        v
  DATA (bidirectional)   [opaque ciphertext bytes]
        |
        v
 CLOSE or ERROR (either side)
        |
        v
   stream_id retired
```

A stream id is never reused within a single mux connection. After the
WebSocket reconnects, the relay restarts its monotonic counter at 1.

### Keepalive

Two layers run concurrently:

1. **WebSocket-level `Ping`/`Pong`**: the relay sends a WS `Ping` every
   `ws_ping_interval_secs` (default 30 s). The read loop's `ws_read_timeout_secs`
   deadline tears the session down if no message arrives in that window. This
   is the relay's primary liveness signal.
2. **Mux-level `PING`/`PONG`**: the device originates application-layer
   `PING` frames (stream_id 0, u64-BE nonce). The relay echoes the nonce in
   a `PONG`. Used by the device's half-open detector (#179).

### Maximum concurrent streams

`limits.max_streams_per_device` (default 32) is the per-device cap. When
reached, the device is expected to respond with `ERROR(STREAM_REFUSED)` for
further `OPEN`s. The relay does not enforce a hard cap on its own.

---

## Internal FCM wakeup

The relay does **not** expose an HTTP `/wake` endpoint. Wakeup is part of the
passthrough resolver and runs only when an MCP client connects to a device
subdomain. See `relay/src/passthrough.rs::resolve_device_stream`:

1. SNI extracted from the client's TLS ClientHello selects the
   `(subdomain, integration_secret)` pair.
2. The integration secret is checked against the in-memory `valid_secrets`
   cache (or Firestore on cache miss) — mismatches are silently rejected to
   avoid leaking subdomain existence.
3. If a mux session is registered for the subdomain, a stream is opened
   directly.
4. Otherwise the relay consults `FcmWakeThrottle` (one wake per subdomain
   within a short cooldown — by default 10 s — so concurrent clients share a
   single FCM push). If the throttle allows, an FCM `type: "wake"` message
   is sent to `devices/{subdomain}.fcm_token` with priority `high`.
5. The handler then waits up to `limits.fcm_wakeup_timeout_secs` (default
   20 s) for the device's WebSocket upgrade to complete; on connect, it
   opens the stream. Otherwise the client TCP connection is closed without
   forwarding any data.

There is no application-layer error envelope on this path: the relay simply
fails the TLS connection (the client sees the TCP socket close) since it is
TLS-passthrough, not an HTTP endpoint. `pending_fcm_wakeups` on `/status`
counts the wait queue.

---

## FCM message formats

All FCM messages are **data-only** (no `notification` block) so the device's
`FirebaseMessagingService` always handles them, even when the app is in the
background. Sent via the FCM HTTP v1 API:

```
POST https://fcm.googleapis.com/v1/projects/{project_id}/messages:send
```

`relay/src/fcm.rs::FcmData`:

```json
{
  "type": "wake"
}
```

The `data` payload has a single field:

| Field | Type | Description |
|---|---|---|
| `type` | string | One of `"wake"` or `"renew"`. |

Wrapped in the FCM v1 send envelope:

```json
{
  "message": {
    "token": "<device_fcm_token>",
    "android": { "priority": "high" | "normal" },
    "data": { "type": "wake" }
  }
}
```

### `wake`

Sent by the passthrough resolver when a client connects and no mux session
is registered. Priority `high` (FCM attempts immediate delivery, waking
Doze).

**Expected device behavior:** start the foreground tunnel service and
upgrade to the relay's `/ws` using the compiled-in URL.

### `renew`

Sent by the maintenance loop (`relay/src/maintenance.rs`) when a device's
`cert_expires` is within `cert_expiry_nudge_days` (default 7 d) and
`renewal_nudge_sent` is null. Priority `normal` (delivery may be delayed by
Doze; renewal is not time-critical).

**Expected device behavior:** enqueue a WorkManager task that calls
`POST /renew` and surfaces a notification if the user needs to act.

---

## Firestore document schemas

### `devices/{subdomain}`

Primary device record. Document id is the subdomain string (e.g. `abc123`).
See `relay/src/firestore.rs::DeviceRecord`.

| Field | Firestore type | Required | Description |
|---|---|---|---|
| `fcm_token` | string | yes | Current FCM registration token. |
| `firebase_uid` | string | yes | Firebase anonymous-auth UID. Immutable for the lifetime of the subdomain. |
| `public_key` | string | yes after round 2 | Base64-encoded DER `SubjectPublicKeyInfo` (ECDSA P-256), extracted from the most recent CSR. Empty between round 1 and round 2. |
| `cert_expires` | timestamp | yes after round 2 | Expiration of the most recently issued certificate. `UNIX_EPOCH` between round 1 and round 2. |
| `registered_at` | timestamp | yes | When this subdomain was first created. |
| `last_rotation` | timestamp | optional | When this Firebase UID last rotated subdomains. Used to enforce the rotation cooldown. |
| `renewal_nudge_sent` | timestamp | optional | Set when the relay sends a `type: "renew"` FCM. Cleared on successful `/renew`. Prevents duplicate nudges. |
| `secret_prefix` | string | optional, **deprecated** | Legacy single-secret field from the pre-#148 era. Still consulted as a fallback in `passthrough.rs::resolve_device_stream` for devices that have not migrated. New registrations leave this `null`. |
| `valid_secrets` | array of string | yes (may be empty) | Flat membership list used by the SNI fast path. Each entry is a valid first-label for the device's hostname (e.g. `brave-health`). Authoritative for SNI routing; derived from `integration_secrets` on writes that populate both. |
| `integration_secrets` | map (string → string) | yes (may be empty) | Integration id → its `{adjective}-{integration_id}` secret. Source of truth for which secret belongs to which integration; needed for `/rotate-secret` replace-by-request-set semantics (#285). |

**Access patterns**

- Relay reads/writes via the Firebase Admin SDK (service-account credentials).
- Device writes only `fcm_token` (via Firestore security rules matching
  `firebase_uid`) — currently performed over the WebSocket text control
  channel rather than a direct Firestore write.
- Direct doc lookup: `devices/{subdomain}`.
- UID query: `where firebase_uid == {uid}` for `/register` re-registration
  detection.
- Maintenance: `where cert_expires < now + 7d AND cert_expires > now AND
  renewal_nudge_sent == null` for nudges; `where cert_expires < now - 180d`
  for stale-device sweep / subdomain reclaim.

### `pending_certs/{subdomain}`

Devices blocked on ACME rate limits. `relay/src/firestore.rs::PendingCert`.

| Field | Firestore type | Required | Description |
|---|---|---|---|
| `fcm_token` | string | yes | Device's FCM token at the time of the blocked request. |
| `csr` | string | yes | Base64-encoded DER CSR that was blocked. |
| `blocked_at` | timestamp | yes | When the request was blocked. |
| `retry_after` | timestamp | yes | Earliest time the maintenance job should retry issuance. |

**Lifecycle**

1. Created by `/register/certs` or `/renew` when the ACME CA returns a rate
   limit.
2. Maintenance job retries when `retry_after <= now`.
3. On success: update `devices/{subdomain}.cert_expires`, delete
   `pending_certs/{subdomain}`, send `type: "wake"` FCM so the device picks
   up the new cert.
4. On failure: bump `retry_after` and leave the doc.

### `subdomain_reservations/{subdomain}`

Short-lived holds created by `/request-subdomain`. Document id is the
reserved subdomain. `relay/src/firestore.rs::SubdomainReservation`.

| Field | Firestore type | Required | Description |
|---|---|---|---|
| `fqdn` | string | yes | Full FQDN (`{subdomain}.{base_domain}`). |
| `firebase_uid` | string | yes | UID that holds the reservation. |
| `expires_at` | timestamp | yes | Absolute expiry. After this point the name returns to the pool. |
| `base_domain` | string | yes | Base domain the reservation lives under. |
| `created_at` | timestamp | yes | When the reservation was created. |

**Lifecycle**

1. Created by `/request-subdomain`. TTL =
   `limits.subdomain_reservation_ttl_secs` (default 600 s).
2. Consumed by the next successful `POST /register` from the same UID
   (single-use).
3. Expired entries are dropped opportunistically by `/request-subdomain` when
   the same UID retries, and by the maintenance sweep.

---

## Signature semantics by endpoint

The relay verifies ECDSA P-256 signatures in three different shapes. They are
**not interchangeable** — using the wrong payload returns `403 forbidden:
Signature verification failed`. The signed bytes differ even though the
algorithm and stored public key are the same.

| Endpoint | When required | Payload signed | Where verified |
|---|---|---|---|
| `POST /register` (re-registration / rotation) | UID already has a subdomain. | **Raw bytes of the `firebase_token` string.** | `relay/src/api/register.rs:144-152` |
| `POST /register/certs` (PoP path) | `devices/{subdomain}.public_key` already populated. | **Raw DER bytes of the CSR** (i.e. the bytes that would be Base64-encoded to produce the `csr` field). | `relay/src/api/register.rs:357-371` |
| `POST /renew` (always) | Always. | **Raw DER bytes of the CSR.** | `relay/src/api/renew.rs:71-78,123,217` |
| `POST /rotate-secret` | n/a — no body signature. Authenticated by mTLS only. | — | — |
| `GET /ws` | n/a — authenticated by mTLS only. | — | — |

### Algorithm

- **Key:** ECDSA P-256 (secp256r1).
- **Hash:** SHA-256 (`SHA256withECDSA`, FIPS 186-4).
- **Encoding:** DER-encoded `ECDSA-Sig-Value` (two `INTEGER`s, `r` and `s`),
  then Base64-encoded for the JSON body.

### Verification procedure

1. Base64-decode the `signature` field → DER signature bytes.
2. Determine the **payload bytes** for this endpoint (token bytes vs CSR
   DER) — this is the step that diverges between endpoints.
3. Look up `devices/{subdomain}.public_key`; Base64-decode → DER `SPKI`.
4. Parse the SPKI as an ECDSA P-256 verifying key.
5. `verifying_key.verify(payload_bytes, signature)`. On error → 403.

---

## Rate limiting summary

| Endpoint | Scope | Limit | Mechanism | State |
|---|---|---|---|---|
| `POST /request-subdomain` | Per Firebase UID | Token bucket: burst `request_subdomain_rate_burst`, refill 1 token / `request_subdomain_rate_refill_secs`. | `RateLimiter` keyed by UID. | In-memory (resets on relay restart). Periodic sweep drops idle entries. |
| `POST /register` (`force_new`) | Per Firebase UID | Once per `subdomain_rotation_cooldown_days` (default 30). | `last_rotation` timestamp on `devices/{subdomain}`. | Firestore. |
| `POST /register/certs` / `POST /renew` (ACME) | Global, per ACME account | CA-specific. GTS in production has tens-of-thousands/day headroom; Let's Encrypt deployments are bounded by 50 certs / registered domain / week. | Returned by the CA. The relay surfaces it as `acme_rate_limited` and stores blocked work in `pending_certs/`. | CA + Firestore. |
| Internal passthrough (per subdomain) | Per subdomain | One FCM wake per subdomain per `FcmWakeThrottle` window (default 10 s). | `FcmWakeThrottle`. | In-memory. |
| Internal passthrough (per source IP / subdomain) | Per (IP, subdomain) | `conn_rate_limit_max` connections / `conn_rate_limit_window_secs` (default 200 / 60 s) — defends against scanners hammering one device from one IP. | `ConnectionRateLimiter`. | In-memory. |

The ACME rate-limit counter is best-effort: on relay restart it resets, and
the CA's authoritative limit is the binding one. In practice GTS-backed
production rarely approaches its ceiling; the `pending_certs/` queue plus the
`acme_rate_limited` envelope mostly exist to make Let's Encrypt-backed
self-hosts survive a retry storm gracefully.

---

## Admin / Test-Mode Endpoints

A separate admin HTTP server is gated by both:

- the `test-mode` Cargo feature (compile-time);
- the `--test-mode <port>` CLI flag (or `TEST_MODE_PORT` env var) at runtime.

When enabled, the admin server binds **only on `127.0.0.1:<port>`** and is
unauthenticated. **Release builds do not include this code at all** — the
entire `relay/src/test_mode.rs` module is `#[cfg(feature = "test-mode")]`.

The admin endpoints exist to drive failure scenarios from the
`core/tunnel`/`:app` integration tests where a real ClientHello/mTLS dance
is impractical (Conscrypt's SNI suppression under Robolectric, FCM
unavailability in CI, etc.). See issues #249, #266, #377.

| Method | Path | Query | Behavior |
|---|---|---|---|
| `POST` | `/test/kill-ws` | `subdomain` | Aborts the active mux WebSocket without sending a close frame. Simulates relay-side network drop / crash so the device-side half-open detector fires. Returns `{killed: bool, subdomain}`. |
| `POST` | `/test/fcm-wake` | `subdomain` | Records a synthetic FCM wake event in the in-memory metrics (no real FCM is sent). Returns `{captured: <count>}`. |
| `POST` | `/test/open-stream` | `subdomain`, `stream_id`, `sni` (optional) | Pushes an OPEN frame onto the mux session as if passthrough had routed an inbound client connection. The frame's payload is the supplied SNI string. Returns `{opened: bool, subdomain, stream_id}` (404 if no session, 409 on emit failure). |
| `POST` | `/test/emit-stream-error` | `subdomain`, `stream_id`, `code` (optional, default `2` = `STREAM_RESET`), `message` (optional) | Pushes an ERROR frame onto an already-open stream. `code` is a `MuxErrorCode` (1..=4); 400 on invalid code. |
| `GET`  | `/test/wait-session-registered` | `subdomain`, `timeout_ms` (optional, default 5000) | Blocks until the named subdomain's mux session is registered (or the timeout elapses). Returns immediately if already registered. Replaces the hardcoded 500 ms sleep that previously raced session insertion (#377). |
| `GET`  | `/test/stats` | — | Returns the `TestMetrics` snapshot: per-endpoint call counters, last-seen mTLS presence per path, captured synthetic wakes, and the list of SNI hostnames the SNI router actually dispatched. |

The admin router is built by
`relay/src/test_mode.rs::build_admin_router` and bound on loopback by
`spawn_admin_server`. None of these endpoints participate in graceful
shutdown — the test harness terminates the relay subprocess directly.

When `test-mode` is enabled, the production API router is also wrapped with
the `record_api_request` middleware so every relay-API call is counted in
`TestMetrics` (visible via `/test/stats`). In a release build that middleware
is not even compiled in.
