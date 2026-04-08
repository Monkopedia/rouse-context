# Secret Prefix Bot Rejection

## Problem

The current bot protection (FCM wake throttle + IP rate limiting) stops spamming but doesn't prevent sporadic wakeups. Any bot that discovers a valid subdomain (`abc123.rousecontext.com`) can wake the device by connecting to it. The relay must send FCM before it can know whether the connection is legitimate.

## Solution

Add a secret prefix to the device URL: `secret.device.rousecontext.com`. The relay validates the secret against a stored value before waking the device. Bots that discover the device subdomain but don't know the secret are rejected at the TCP level — no FCM, no wake, no response.

## URL Format

Before: `https://abc123.rousecontext.com/health/mcp`
After: `https://brave-falcon.abc123.rousecontext.com/health/mcp`

- `brave-falcon` — secret prefix (two-word, same word list as device subdomains, user-typeable)
- `abc123` — stable device identity (unchanged from current)
- Secret can be rotated without changing device identity or reissuing certs

## Changes

### DNS (Cloudflare)

Change per-device record from:
```
abc123.rousecontext.com.  A  <relay-ip>
```
To:
```
*.abc123.rousecontext.com.  CNAME  relay.rousecontext.com.
```

One wildcard CNAME per device. Created during onboarding (replaces current A record creation). Any prefix resolves to the relay.

### ACME Certificates

Change cert request from:
```
abc123.rousecontext.com
```
To:
```
*.abc123.rousecontext.com
```

Still DNS-01 challenge via Cloudflare API. TXT record: `_acme-challenge.abc123.rousecontext.com`. The wildcard cert covers all prefixes — rotation doesn't require new certs.

### Relay SNI Routing (`sni.rs`)

Current `RouteDecision::from_sni` rejects multi-level subdomains (`!subdomain.contains('.')`). Change to:

1. Strip `.{base_domain}` suffix as before
2. If remainder contains a dot: split into `secret.device`
3. Look up device, validate secret against Firestore `secret_prefix` field
4. If valid → `DevicePassthrough { subdomain: device }`
5. If invalid or missing → `Reject` (silent TCP close, no FCM)
6. If no dot (bare subdomain, old format) → `Reject` (force migration)

`RouteDecision::DevicePassthrough` gains a field:
```rust
DevicePassthrough {
    subdomain: String,      // device identity
    secret_prefix: String,  // validated prefix (for logging)
}
```

### Relay Secret Validation (`passthrough.rs`)

Before attempting FCM wake or stream open:
1. Look up device record (cache or Firestore)
2. Compare SNI secret prefix against `device.secret_prefix`
3. Reject if mismatch — `PassthroughError::InvalidSecret`

This is a pure string comparison on an already-cached field. Zero cost.

New error variant:
```rust
InvalidSecret,
```

### Relay Registration (`api/register.rs`)

On device registration:
1. Generate device subdomain (existing: two-word `{adj}-{noun}`)
2. Generate secret prefix (same word list, same format: `{adj}-{noun}`)
3. Store `secret_prefix` in Firestore device record
4. Create wildcard DNS: `*.{subdomain}.rousecontext.com`
5. Request wildcard ACME cert: `*.{subdomain}.rousecontext.com`
6. Return both `subdomain` and `secret_prefix` to device

Response changes:
```json
{
  "subdomain": "abc123",
  "secret_prefix": "brave-falcon",
  "cert": "base64-encoded-cert-chain"
}
```

### Relay Secret Rotation

New endpoint or extend existing `/register` with `rotate_secret: true`:

1. Device sends authenticated request (mTLS or Firebase token)
2. Relay generates new secret prefix
3. Updates Firestore `secret_prefix` field
4. Returns new prefix
5. Old prefix is immediately invalid

No DNS or cert changes needed — wildcard covers all prefixes.

### Firestore Device Record

Add field:
```
secret_prefix: "brave-falcon"
```

### Device — CertificateStore Interface

Add to `CertificateStore`:
```kotlin
suspend fun storeSecretPrefix(prefix: String)
suspend fun getSecretPrefix(): String?
```

The full MCP URL is now: `https://{secretPrefix}.{subdomain}.{baseDomain}/{integration}/mcp`

### Device — Onboarding Flow

`OnboardingFlow` parses and stores both `subdomain` and `secret_prefix` from `/register` response.

### Device — Secret Rotation

"Generate new address" in Settings:
1. Calls relay endpoint to rotate secret
2. Stores new prefix in CertificateStore
3. UI updates displayed URL
4. No cert reissue, no DNS change, instant effect

### App UI

URL display format changes from:
```
https://abc123.rousecontext.com/health/mcp
```
To:
```
https://brave-falcon.abc123.rousecontext.com/health/mcp
```

All places that build/display URLs need updating:
- `IntegrationManageViewModel` (URL construction)
- `IntegrationEnabledScreen` (URL display)
- `MainDashboardScreen` (integration URL)
- OAuth discovery endpoints (issuer URL)

## Migration

Existing devices (already onboarded with bare subdomain):
- On first connect after update, device calls rotation endpoint to get a secret prefix
- Relay creates wildcard DNS alongside existing A record
- Once secret prefix is set, bare subdomain connections are rejected
- Old A record can be cleaned up by maintenance job after migration window

## Security Properties

- **Pre-wake rejection**: Invalid secret → TCP close before FCM. Zero battery cost.
- **Enumeration resistance**: Knowing `abc123.rousecontext.com` exists is useless without the prefix. ~4M prefix combinations per device.
- **Rotation**: Compromised URL → rotate prefix → old URL dead instantly. No cert reissue.
- **No timing oracle**: Reject happens at SNI parse time, same code path regardless of whether device exists.
- **Wildcard cert**: One cert per device, valid for all prefixes. Rotation is free.

## Non-Goals

- Automatic rotation on failed attempts (manual only, per user preference)
- Prefix complexity requirements (two-word format is sufficient for anti-bot, not meant as crypto)
- Backward compatibility with bare subdomain URLs (force migration after grace period)
