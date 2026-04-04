# Relay Server API Specification

Complete API reference for the Rouse Context relay server (`relay.rousecontext.com`).

## Conventions

### Base URL

All REST endpoints are served over HTTPS at `https://relay.rousecontext.com` (port 443). The relay terminates TLS for its own hostname only. Device subdomain traffic is pure TLS passthrough and is not covered in this document (see `relay.md` for passthrough behavior).

### Authentication Methods

Three authentication methods are used across endpoints:

| Method | Mechanism | When Used |
|---|---|---|
| **mTLS** | TLS client certificate presented during handshake, verified against Let's Encrypt root certs (ISRG Root X1, X2). Subdomain extracted from certificate CN or first SAN dNSName matching `*.rousecontext.com`. | `/ws`, `/renew` (valid cert path) |
| **Firebase ID Token** | `firebase_token` field in request body. Verified via Google's public keys (RS256 JWT). Must match project ID. Extracts `sub` claim as Firebase UID. | `/register`, `/renew` (expired cert path) |
| **Unauthenticated** | No credentials required. | `/wake/:subdomain`, `/status` |

TLS client certificate is **optional** at the TLS level (`allow_unauthenticated()` in rustls config). Endpoints that require mTLS enforce it at the application layer and return 401 if no valid certificate was presented.

### Common Error Response Envelope

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
| `error` | string | always | Machine-readable error code (see table below) |
| `message` | string | always | Human-readable description, suitable for logging but not for display to end users |
| `retry_after_secs` | u64 | only for `rate_limited` | Seconds until the client should retry |

### Error Codes

| Code | HTTP Status | Description |
|---|---|---|
| `unauthorized` | 401 | Missing or invalid authentication credentials |
| `forbidden` | 403 | Valid credentials but insufficient permissions (e.g. Firebase UID mismatch) |
| `not_found` | 404 | Subdomain not found in device registry |
| `bad_request` | 400 | Malformed request body, missing required fields, or invalid field values |
| `rate_limited` | 429 | Too many requests; includes `retry_after_secs` |
| `acme_rate_limited` | 429 | ACME CA rate limit hit; includes `retry_after_secs` (typically 604800 = 1 week) |
| `cooldown` | 429 | Subdomain rotation attempted within 30-day cooldown; includes `retry_after_secs` |
| `acme_failure` | 502 | ACME challenge or cert issuance failed |
| `timeout` | 504 | Device did not connect within the wakeup timeout window |
| `internal` | 500 | Unexpected server error |

### Content Type

All request and response bodies are `application/json` unless otherwise noted. The `/ws` endpoint upgrades to WebSocket.

---

## Endpoints

### 1. `GET /ws` -- Mux WebSocket

Establishes a multiplexed WebSocket connection from a device to the relay. All MCP client traffic for this device is routed through this connection using the binary mux framing protocol.

#### Authentication

**mTLS required.** The device must present a valid TLS client certificate issued by a trusted CA (Let's Encrypt). The relay extracts the subdomain from the certificate's CN or SAN `dNSName` field (first entry matching `*.rousecontext.com`, stripping the domain suffix).

#### Request

```
GET /ws HTTP/1.1
Host: relay.rousecontext.com
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Version: 13
Sec-WebSocket-Key: <base64-encoded-key>
```

No request body. No query parameters. No additional headers beyond standard WebSocket upgrade.

#### Success Response

HTTP 101 Switching Protocols. The connection is upgraded to a WebSocket carrying binary mux frames (see Mux Binary Framing Protocol below).

#### Error Responses

| Status | Condition | Body |
|---|---|---|
| 401 | No client certificate presented, or certificate is invalid/expired/untrusted | `{"error": "unauthorized", "message": "Valid client certificate required"}` |
| 401 | Certificate CN/SAN does not contain a valid `*.rousecontext.com` subdomain | `{"error": "unauthorized", "message": "Certificate does not contain a valid device subdomain"}` |
| 409 | Device already has an active mux connection from another session | `{"error": "conflict", "message": "Device already has an active mux connection"}` |

#### Behavior After Upgrade

- The relay registers this WebSocket as the active mux connection for the extracted subdomain.
- Any existing mux connection for the same subdomain is replaced (old WebSocket closed with 1000 Normal Closure).
- WebSocket ping frames are sent every 30 seconds. If a pong is not received within 30 seconds, the connection is considered dead and torn down.
- On WebSocket close (from either side), all active streams for this device are torn down and all corresponding client TCP connections are closed.

---

### 2. `POST /register` -- Device Registration

Onboards a new device or rotates the subdomain of an existing device. Performs ACME DNS-01 challenge and returns a signed certificate.

#### Authentication

**Firebase ID Token** in request body. For re-registration or rotation of an existing subdomain, a signature is also required to prove control of the original private key.

#### Request

```
POST /register HTTP/1.1
Host: relay.rousecontext.com
Content-Type: application/json
```

```json
{
  "firebase_token": "eyJhbGciOiJSUzI1NiIs...",
  "csr": "MIIBIjANBgkqhki...",
  "fcm_token": "dGVzdF9mY21fdG9rZW4...",
  "signature": "MEUCIQD...",
  "force_new": false
}
```

| Field | Type | Required | Constraints | Description |
|---|---|---|---|---|
| `firebase_token` | string | always | Valid Firebase ID token JWT for this project | Firebase anonymous auth ID token |
| `csr` | string | always | Base64-encoded DER PKCS#10 CSR, signed with ECDSA P-256 key | Certificate Signing Request |
| `fcm_token` | string | always | Non-empty string | Firebase Cloud Messaging registration token |
| `signature` | string | conditional | Base64-encoded DER ECDSA signature over the raw (pre-Base64) CSR bytes using SHA256withECDSA | Required when the Firebase UID already has a registered subdomain. Signs the CSR DER bytes (not the Base64 string). |
| `force_new` | boolean | no (default: `false`) | | When `true`, requests a new subdomain (rotation). Old subdomain is invalidated immediately. Subject to 30-day cooldown. |

#### Success Response -- 200 OK

```json
{
  "subdomain": "brave-falcon",
  "cert": "MIICpDCCAYwCCQD...",
  "relay_host": "relay.rousecontext.com"
}
```

| Field | Type | Description |
|---|---|---|
| `subdomain` | string | Assigned subdomain (without domain suffix). Full hostname is `{subdomain}.rousecontext.com`. |
| `cert` | string | Base64-encoded DER certificate chain (leaf + intermediates), PEM-concatenated then Base64-encoded |
| `relay_host` | string | Hostname the device should connect to for the mux WebSocket |

#### Error Responses

| Status | Condition | Error Code | Example Message |
|---|---|---|---|
| 400 | Missing required field, malformed CSR, invalid Base64, CSR not signed with P-256 | `bad_request` | `"Invalid CSR: expected ECDSA P-256 key"` |
| 400 | CSR subject does not pass validation | `bad_request` | `"CSR validation failed"` |
| 401 | Firebase token invalid, expired, or wrong project | `unauthorized` | `"Invalid Firebase ID token"` |
| 403 | Firebase UID already registered but `signature` missing | `forbidden` | `"Signature required for re-registration"` |
| 403 | `signature` does not verify against stored public key | `forbidden` | `"Signature verification failed"` |
| 429 | `force_new: true` but last rotation was less than 30 days ago | `cooldown` | `"Subdomain rotation available after 2026-05-01"` |
| 429 | ACME CA rate limit reached (50 certs/week for `rousecontext.com`) | `acme_rate_limited` | `"Certificate issuance rate limited"` |
| 502 | ACME challenge failed (DNS propagation timeout, CA rejection, Cloudflare API failure after 3 retries) | `acme_failure` | `"ACME DNS-01 challenge failed"` |
| 500 | Firestore write failure, unexpected internal error | `internal` | `"Internal server error"` |

#### Behavior Details

1. Verify Firebase ID token. Extract UID from `sub` claim.
2. Query Firestore for any `devices/` document where `firebase_uid == UID`.
3. **New UID (no existing record):**
   - `signature` field is ignored if present.
   - Generate random two-word subdomain (`{adjective}-{noun}`). Check Firestore for collision, retry with new words if taken.
   - Extract public key from CSR's SubjectPublicKeyInfo.
4. **Existing UID, `force_new: false` (re-registration):**
   - `signature` is required. Verify it against the stored `public_key` for the existing subdomain.
   - Reuse the existing subdomain. Extract new public key from CSR (key rotation is allowed).
5. **Existing UID, `force_new: true` (rotation):**
   - `signature` is required. Verify against stored `public_key`.
   - Check `last_rotation` timestamp. If less than 30 days ago, return 429 `cooldown`.
   - Generate new random subdomain. Delete old `devices/` document. All client tokens for the old subdomain are implicitly revoked (device-side responsibility).
6. Check ACME weekly issuance count. If at or above 50, store in `pending_certs/{subdomain}` and return 429 `acme_rate_limited`.
7. Perform ACME DNS-01 challenge for `{subdomain}.rousecontext.com` (see ACME Orchestration in `relay.md`).
8. Write/update `devices/{subdomain}` document in Firestore.
9. Return certificate chain, subdomain, and relay host.

#### Example

Request:
```json
{
  "firebase_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJhYmMxMjMiLCJhdWQiOiJyb3VzZS1jb250ZXh0IiwiZXhwIjoxNzE3NTMyODAwfQ.signature",
  "csr": "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA0Z3...",
  "fcm_token": "eKJf8sM4TfKxHcLj:APA91bH...",
  "force_new": false
}
```

Response (200):
```json
{
  "subdomain": "brave-falcon",
  "cert": "LS0tLS1CRUdJTi...",
  "relay_host": "relay.rousecontext.com"
}
```

---

### 3. `POST /renew` -- Certificate Renewal

Renews the TLS certificate for a registered device. Supports two authentication paths depending on whether the device's current certificate is still valid.

#### Authentication

**Path A -- mTLS (valid cert):** Device presents its still-valid client certificate. No Firebase token needed. Subdomain is extracted from the certificate.

**Path B -- Firebase + Signature (expired cert):** Device provides Firebase ID token, subdomain, and a signature over the CSR to prove key ownership.

The relay determines which path to use based on whether a valid client certificate was presented in the TLS handshake.

#### Request -- Path A (mTLS)

```
POST /renew HTTP/1.1
Host: relay.rousecontext.com
Content-Type: application/json
```

```json
{
  "csr": "MIIBIjANBgkqhki..."
}
```

| Field | Type | Required | Constraints | Description |
|---|---|---|---|---|
| `csr` | string | always | Base64-encoded DER PKCS#10 CSR, ECDSA P-256 | New Certificate Signing Request |

#### Request -- Path B (Firebase + Signature)

```
POST /renew HTTP/1.1
Host: relay.rousecontext.com
Content-Type: application/json
```

```json
{
  "subdomain": "brave-falcon",
  "firebase_token": "eyJhbGciOiJSUzI1NiIs...",
  "csr": "MIIBIjANBgkqhki...",
  "signature": "MEUCIQD..."
}
```

| Field | Type | Required | Constraints | Description |
|---|---|---|---|---|
| `subdomain` | string | Path B only | Must match an existing `devices/` document | The device's assigned subdomain |
| `firebase_token` | string | Path B only | Valid Firebase ID token JWT | Firebase anonymous auth ID token |
| `csr` | string | always | Base64-encoded DER PKCS#10 CSR, ECDSA P-256 | New Certificate Signing Request |
| `signature` | string | Path B only | Base64-encoded DER ECDSA signature over raw CSR DER bytes using SHA256withECDSA | Proves control of the registered private key |

#### Success Response -- 200 OK

```json
{
  "cert": "MIICpDCCAYwCCQD..."
}
```

| Field | Type | Description |
|---|---|---|
| `cert` | string | Base64-encoded DER certificate chain (leaf + intermediates), PEM-concatenated then Base64-encoded |

#### Error Responses

| Status | Condition | Error Code | Example Message |
|---|---|---|---|
| 400 | Missing required fields for the detected auth path, malformed CSR | `bad_request` | `"Missing required field: csr"` |
| 401 | Path A: no client cert or cert invalid/untrusted | `unauthorized` | `"Valid client certificate required"` |
| 401 | Path B: Firebase token invalid or expired | `unauthorized` | `"Invalid Firebase ID token"` |
| 403 | Path B: Firebase UID does not match the `devices/{subdomain}` record | `forbidden` | `"Firebase UID does not match device record"` |
| 403 | Path B: signature does not verify against stored public key | `forbidden` | `"Signature verification failed"` |
| 404 | Path B: subdomain not found in Firestore | `not_found` | `"Device not found"` |
| 429 | ACME CA rate limit reached | `acme_rate_limited` | `"Certificate issuance rate limited"` |
| 502 | ACME challenge failed | `acme_failure` | `"ACME DNS-01 challenge failed"` |
| 500 | Internal error | `internal` | `"Internal server error"` |

#### Behavior Details

1. **Detect auth path:** If a valid TLS client certificate was presented and verified, use Path A. Otherwise, use Path B.
2. **Path A:** Extract subdomain from certificate CN/SAN. Look up `devices/{subdomain}` in Firestore to confirm it exists.
3. **Path B:** Verify Firebase token. Extract UID. Look up `devices/{subdomain}`. Verify `firebase_uid` matches. Verify `signature` against stored `public_key` (the signature is over the raw DER bytes of the CSR, not the Base64 encoding).
4. Perform ACME DNS-01 challenge for `{subdomain}.rousecontext.com`.
5. Update `cert_expires` in `devices/{subdomain}`. Clear `renewal_nudge_sent` to `null`.
6. Return new certificate chain.

#### Example -- Path A (mTLS)

Request (with valid client cert in TLS handshake):
```json
{
  "csr": "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA0Z3..."
}
```

Response (200):
```json
{
  "cert": "LS0tLS1CRUdJTi..."
}
```

#### Example -- Path B (expired cert)

Request:
```json
{
  "subdomain": "brave-falcon",
  "firebase_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "csr": "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA0Z3...",
  "signature": "MEUCIQDrZ3K4q..."
}
```

Response (200):
```json
{
  "cert": "LS0tLS1CRUdJTi..."
}
```

---

### 4. `POST /wake/:subdomain` -- Pre-flight Wakeup

Sends an FCM push to wake a device before the MCP client opens a TLS connection. Allows clients to avoid the cold-start latency on the first real connection.

#### Authentication

**None.** This endpoint is unauthenticated but rate-limited per subdomain.

#### Rate Limiting

Token bucket: **6 requests per subdomain per minute.** The bucket refills at 1 token every 10 seconds. State is held in-memory (resets on relay restart).

When rate limited, the response includes a `Retry-After` header (seconds).

#### Request

```
POST /wake/brave-falcon HTTP/1.1
Host: relay.rousecontext.com
```

No request body. The subdomain is in the URL path.

#### Path Parameters

| Parameter | Type | Constraints | Description |
|---|---|---|---|
| `subdomain` | string | Must match `[a-z0-9]+(-[a-z0-9]+)*`, 3-63 characters | The device's assigned subdomain |

#### Success Response -- 200 OK

Returned when the device already has an active mux connection, or when the device successfully connects within the timeout window after FCM wakeup.

```json
{
  "status": "ready"
}
```

#### Error Responses

| Status | Condition | Error Code | Example Message |
|---|---|---|---|
| 404 | Subdomain not found in Firestore (or in-memory cache) | `not_found` | `"Device not found"` |
| 429 | Rate limit exceeded for this subdomain | `rate_limited` | `"Rate limit exceeded"` |
| 504 | FCM sent but device did not connect within 20 seconds | `timeout` | `"Device did not connect within timeout"` |
| 500 | FCM send failure, Firestore lookup failure | `internal` | `"Failed to send wakeup notification"` |

The 429 response includes the header:
```
Retry-After: <seconds until next token available>
```

#### Behavior Details

1. Check in-memory rate limit bucket for the subdomain. If empty, return 429.
2. Look up `devices/{subdomain}` in Firestore (with in-memory cache, TTL-based).
3. If device has an active mux WebSocket connection, return 200 immediately.
4. If device is offline, send FCM `type: "wake"` message (see FCM Message Formats below).
5. Hold the HTTP request open. Wait for the device to establish a mux WebSocket connection, up to 20 seconds (configurable via `timeouts.fcm_wakeup_secs` in `relay.toml`).
6. If the device connects within the timeout, return 200.
7. If the timeout expires, return 504.

#### Example

Request:
```
POST /wake/brave-falcon HTTP/1.1
Host: relay.rousecontext.com
```

Response (200 -- device was already connected):
```json
{
  "status": "ready"
}
```

Response (504 -- device did not wake):
```json
{
  "error": "timeout",
  "message": "Device did not connect within timeout"
}
```

---

### 5. `GET /status` -- Health and Metrics

Returns relay operational metrics. Intended for monitoring systems.

#### Authentication

**None.** Publicly accessible.

#### Request

```
GET /status HTTP/1.1
Host: relay.rousecontext.com
```

No request body. No query parameters.

#### Success Response -- 200 OK

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
| `uptime_secs` | u64 | Seconds since the relay process started. Monotonically increasing. |
| `active_mux_connections` | u32 | Number of device mux WebSocket connections currently open |
| `active_streams` | u32 | Total number of active mux streams across all devices (each stream corresponds to one client TCP connection being spliced) |
| `total_sessions_served` | u64 | Cumulative count of streams that have been opened since the relay started (includes streams that have since closed) |
| `pending_fcm_wakeups` | u32 | Number of client connections or `/wake` requests currently waiting for a device to connect after FCM was sent |

#### Error Responses

| Status | Condition | Error Code |
|---|---|---|
| 500 | Internal error (should be extremely rare) | `internal` |

---

## Mux Binary Framing Protocol

Transport: binary WebSocket frames over the mTLS connection established via `GET /ws`.

### Frame Layout

Every WebSocket binary message contains exactly one mux frame:

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|     type      |                        stream_id                              |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|               |                                                               |
+-+-+-+-+-+-+-+-+                                                               |
|                            payload (variable length)                          |
|                                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

| Offset | Size | Field | Encoding |
|---|---|---|---|
| 0 | 1 byte | `type` | unsigned 8-bit integer |
| 1 | 4 bytes | `stream_id` | unsigned 32-bit integer, big-endian (network byte order) |
| 5 | variable | `payload` | depends on `type` |

**Minimum frame size:** 5 bytes (header only, empty payload). A WebSocket binary message shorter than 5 bytes MUST be treated as a protocol error -- log a warning and ignore the frame.

### Message Types

#### DATA (0x01)

Carries opaque stream bytes. This is TLS ciphertext from the client-device session. The relay MUST NOT interpret the payload.

| Field | Value |
|---|---|
| `type` | `0x01` |
| `stream_id` | The stream this data belongs to. MUST NOT be `0`. |
| `payload` | Opaque bytes (1 or more bytes). Empty DATA frames SHOULD NOT be sent but MUST be tolerated. |

**Direction:** Both (relay to device, device to relay).

**Flow:** The relay copies bytes between the client TCP socket and the mux WebSocket using DATA frames. Each byte read from the client TCP socket is sent as (part of) a DATA frame payload. Each DATA frame payload received from the device is written to the corresponding client TCP socket.

There is no fragmentation or reassembly at the mux layer. A single WebSocket message = a single mux frame. The relay may batch or split TCP reads into DATA frames of any size. The device and relay SHOULD send DATA frames promptly rather than buffering large payloads.

#### OPEN (0x02)

Signals the device to accept a new incoming client connection.

| Field | Value |
|---|---|
| `type` | `0x02` |
| `stream_id` | Newly assigned stream ID (relay-assigned, monotonically increasing from 1 per mux connection, never 0) |
| `payload` | SNI hostname, UTF-8 encoded, no null terminator (e.g. `brave-falcon.rousecontext.com`) |

**Direction:** Relay to device only.

**Behavior on relay side:**
1. Assign next available stream ID for this mux connection.
2. Send OPEN frame with the SNI hostname from the client's TLS ClientHello.
3. Immediately after OPEN, send the buffered TLS ClientHello bytes as a DATA frame for the same stream ID.
4. Begin splicing: further reads from the client TCP socket are sent as DATA frames; DATA frames from the device for this stream ID are written to the client TCP socket.

**Behavior on device side:**
1. Create a new `MuxStream` for this stream ID.
2. The first DATA frame(s) will contain the TLS ClientHello. The device performs TLS server accept over the stream.
3. After TLS handshake completes, the device runs the HTTP/MCP server over the plaintext stream.

**Error handling:** If the device cannot accept the stream (e.g. max streams exceeded, internal error), it MUST respond with an ERROR frame for this stream ID with code `STREAM_REFUSED` (0x02).

**Stream ID exhaustion:** Stream IDs are u32. If 2^32 - 1 streams have been opened on a single mux connection, the relay SHOULD close the mux WebSocket and let the device reconnect (this would take years at any realistic rate).

#### CLOSE (0x03)

Signals clean teardown of a stream.

| Field | Value |
|---|---|
| `type` | `0x03` |
| `stream_id` | The stream to close. MUST NOT be `0`. |
| `payload` | Empty (0 bytes). Any payload MUST be ignored by the receiver. |

**Direction:** Both.

**Semantics:** CLOSE is a full close (not half-close). Once a CLOSE is sent or received for a stream ID, no further DATA frames should be sent for that stream ID. DATA frames received after CLOSE for a given stream MUST be silently discarded.

**Relay behavior on CLOSE from device:** Close the corresponding client TCP socket (both read and write halves).

**Relay behavior on client TCP close:** Send CLOSE frame to device for that stream ID.

**Relay behavior on CLOSE from device for unknown stream ID:** Ignore (log at DEBUG level). This can happen due to races.

**Idempotency:** Sending CLOSE for an already-closed stream is harmless. The receiver MUST ignore it.

#### ERROR (0x04)

Reports an error condition for a specific stream or for the connection.

| Field | Value |
|---|---|
| `type` | `0x04` |
| `stream_id` | The stream this error pertains to, or `0` for connection-level errors |
| `payload` | 1 byte error code + UTF-8 error message (may be empty) |

**Payload layout:**

| Offset | Size | Field | Description |
|---|---|---|---|
| 0 | 1 byte | `error_code` | See error code table below |
| 1 | variable | `message` | UTF-8 encoded human-readable message (for logging only, may be empty) |

**Direction:** Both.

**Error Codes:**

| Code | Name | Hex | Description |
|---|---|---|---|
| 1 | `UNKNOWN_STREAM` | `0x01` | DATA or CLOSE received for a stream ID that is not open. Sent by either side when it receives a frame for a stream it does not recognize. |
| 2 | `STREAM_REFUSED` | `0x02` | Device cannot accept a new stream (e.g. max concurrent streams reached, internal error). Sent by device in response to OPEN. |
| 3 | `TIMEOUT` | `0x03` | A timeout occurred (e.g. TLS handshake timeout on device side). |
| 4 | `INTERNAL` | `0x04` | Unspecified internal error. |

**Behavior:** On receiving ERROR for a specific stream (stream_id != 0), the receiver MUST close that stream and the corresponding client TCP connection (if on the relay side). On receiving ERROR with stream_id = 0, the receiver SHOULD close the entire mux connection.

### Stream ID 0

Stream ID 0 is reserved for connection-level signaling. Currently only used with ERROR frames to indicate a fatal mux-level error. DATA, OPEN, and CLOSE frames with stream_id = 0 are invalid and MUST be ignored (log at WARN level).

### Stream Lifecycle

```
Relay assigns stream_id
         |
         v
    OPEN (relay -> device)
         |
         v
    DATA (bidirectional)
         |
         v
  CLOSE or ERROR (either side)
         |
         v
    stream_id retired
```

A stream ID is never reused within a single mux connection. After a mux WebSocket disconnects and the device reconnects, stream IDs reset to 1.

### Keepalive

WebSocket protocol-level ping/pong frames, sent by the relay every 30 seconds (configurable via `timeouts.websocket_ping_secs`). No application-level ping/pong. If a pong is not received before the next ping is due, the mux connection is considered dead.

### Maximum Concurrent Streams

Default: **8** per device (configurable via `timeouts.max_streams_per_device` in `relay.toml`). When the limit is reached, the relay MUST NOT send further OPEN frames. Instead, it closes the incoming client TCP connection immediately. This is enforced relay-side, not by the device -- though the device MAY also refuse streams via ERROR(STREAM_REFUSED) as a defense-in-depth measure.

---

## FCM Message Formats

All FCM messages are **data-only** (no `notification` block) so the device's `FirebaseMessagingService` always receives them, even when the app is in the background.

Sent via FCM HTTP v1 API: `POST https://fcm.googleapis.com/v1/projects/{project_id}/messages:send`

### Wake Message

Sent when a client connects to a device subdomain and the device has no active mux connection, or when `/wake/:subdomain` is called for an offline device.

```json
{
  "message": {
    "token": "<device_fcm_token>",
    "android": {
      "priority": "high"
    },
    "data": {
      "type": "wake",
      "relay_host": "relay.rousecontext.com",
      "relay_port": "443"
    }
  }
}
```

| Data Field | Type | Description |
|---|---|---|
| `type` | string | Always `"wake"` |
| `relay_host` | string | Hostname the device should connect to for the mux WebSocket |
| `relay_port` | string | Port number (always `"443"`, sent as string per FCM data message requirements) |

**Priority:** `high` -- FCM will attempt immediate delivery, waking the device from Doze if necessary.

**Expected device behavior:** Start foreground service, establish mux WebSocket connection to `wss://{relay_host}:{relay_port}/ws` with mTLS.

### Renew Message

Sent by the daily maintenance job when a device's certificate expires within 7 days and no `renewal_nudge_sent` timestamp is set.

```json
{
  "message": {
    "token": "<device_fcm_token>",
    "android": {
      "priority": "normal"
    },
    "data": {
      "type": "renew",
      "relay_host": "relay.rousecontext.com"
    }
  }
}
```

| Data Field | Type | Description |
|---|---|---|
| `type` | string | Always `"renew"` |
| `relay_host` | string | Hostname to use for the renewal `POST /renew` request |

**Priority:** `normal` -- delivery may be delayed by Doze. Renewal is not time-critical.

**Expected device behavior:** Enqueue a WorkManager task to call `POST /renew`. Show a warning notification if appropriate.

---

## Firestore Document Schemas

### `devices/{subdomain}`

Primary device record. The document ID is the subdomain string (e.g. `brave-falcon`).

| Field | Firestore Type | Required | Description |
|---|---|---|---|
| `fcm_token` | string | yes | Current FCM registration token for the device |
| `firebase_uid` | string | yes | Firebase anonymous auth UID. Immutable after creation (for a given subdomain). |
| `public_key` | string | yes | Base64-encoded DER SubjectPublicKeyInfo (ECDSA P-256). Extracted from the CSR at registration time. Updated on re-registration if the device generates a new keypair. |
| `cert_expires` | timestamp | yes | Expiration time of the most recently issued certificate |
| `registered_at` | timestamp | yes | When this subdomain was first created |
| `last_rotation` | timestamp | no | When this Firebase UID last performed a subdomain rotation. Null if never rotated. Used to enforce 30-day cooldown. |
| `renewal_nudge_sent` | timestamp | no | Set when the relay sends a `type: "renew"` FCM push. Cleared (set to null) when the device successfully renews via `POST /renew`. Prevents duplicate nudge FCMs. |

**Access patterns:**
- Relay reads/writes via Firebase Admin SDK (service account credentials).
- Device writes only `fcm_token` (via Firestore security rules matching `firebase_uid`).
- Lookup by subdomain: direct document get (`devices/{subdomain}`).
- Lookup by Firebase UID: query `where firebase_uid == {uid}` (needed for `/register` to check if UID already has a subdomain).
- Daily maintenance: query `where cert_expires < now + 7d AND cert_expires > now AND renewal_nudge_sent == null`.
- Subdomain reclamation: query `where cert_expires < now - 180d`.

**Indexes required:**
- `firebase_uid` -- equality query for registration lookup.
- `cert_expires` -- range query for maintenance jobs.

### `pending_certs/{subdomain}`

Devices blocked on ACME rate limits. Created when a `/register` or `/renew` request hits the weekly cert limit. Processed by the daily maintenance job when the rate limit resets.

| Field | Firestore Type | Required | Description |
|---|---|---|---|
| `fcm_token` | string | yes | Device's FCM token at the time of the blocked request |
| `csr` | string | yes | Base64-encoded DER CSR that was blocked |
| `blocked_at` | timestamp | yes | When the request was blocked |
| `retry_after` | timestamp | yes | Earliest time the relay should attempt to process this entry |

**Lifecycle:**
1. Created by `/register` or `/renew` handler when ACME rate limit is hit.
2. Daily maintenance job checks if `retry_after <= now`. If so, attempts ACME issuance.
3. On successful issuance: update `devices/{subdomain}` with new cert expiry, delete `pending_certs/{subdomain}`, send FCM `type: "wake"` to the device.
4. On failure (rate limit still active): update `retry_after` and leave the document.

---

## Signature Verification

Several endpoints require verifying a signature produced by the device's Android Keystore private key.

### Algorithm

- **Key type:** ECDSA P-256 (secp256r1)
- **Signature algorithm:** SHA256withECDSA (ECDSA with SHA-256 hash, per FIPS 186-4)
- **Signature encoding:** DER-encoded ECDSA-Sig-Value (two INTEGER values: r and s), then Base64-encoded for transmission in JSON

### What Is Signed

The signature is always over the **raw DER bytes of the CSR** -- the bytes that would be Base64-encoded to produce the `csr` field in the request. Not the Base64 string, not the PEM encoding, not a JSON wrapper -- the raw binary CSR.

### Verification Procedure

1. Base64-decode the `signature` field to obtain the DER-encoded ECDSA signature.
2. Base64-decode the `csr` field to obtain the raw CSR bytes.
3. Retrieve the device's stored `public_key` from Firestore (Base64-encoded DER SubjectPublicKeyInfo).
4. Base64-decode the public key.
5. Verify: `SHA256withECDSA.verify(public_key, csr_bytes, signature)`.

---

## Rate Limiting Summary

| Endpoint | Scope | Limit | Mechanism | State |
|---|---|---|---|---|
| `POST /wake/:subdomain` | Per subdomain | 6 requests per minute | Token bucket (1 token per 10 seconds, max burst 6) | In-memory (resets on relay restart) |
| `POST /register` | Global (ACME) | 50 certs per week for `rousecontext.com` | Counter (resets weekly) | In-memory + Firestore `pending_certs/` for overflow |
| `POST /register` (`force_new`) | Per Firebase UID | Once per 30 days | `last_rotation` timestamp in Firestore | Firestore |
| `POST /renew` | Global (ACME) | 50 certs per week (shared with `/register`) | Same counter as `/register` | Same as `/register` |

The ACME rate limit counter is best-effort. On relay restart, the counter resets. The relay can query Firestore `devices/` records with `cert_expires` in the current week to reconstruct an approximate count, but the exact count is not critical -- Let's Encrypt will reject requests that exceed the real limit, and the relay handles that gracefully.
