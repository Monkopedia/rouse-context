# Security

## What is Rouse Context?

Rouse Context is an Android app that turns your phone into an MCP server. AI assistants like Claude connect to a URL, your phone wakes up, and a direct encrypted session is established. The AI asks for what it needs (step count, notifications, whatever you've enabled), your phone responds, and then it goes back to sleep.

## The security promise

**Your phone's data never touches our servers. Here's why.**

The relay server that sits between Claude and your phone is deliberately blind. It can see *that* a connection is happening, but it cannot see *what's being said*. This isn't a policy decision -- it's a technical constraint. The relay physically cannot read your data because the encryption terminates on your phone, not on the relay.

Here's the flow:

```
Claude ──────── TLS tunnel (encrypted) ────────> Your Phone
                      |
                   Relay Server
                (can see: connection exists)
                (cannot see: data content)
```

More precisely:

1. Claude connects to `https://brave-health.cool-penguin.rousecontext.com/mcp`
2. The relay reads just enough of the handshake to figure out which phone to route to (the hostname, visible in the unencrypted SNI field)
3. If your phone is asleep, the relay sends a push notification via FCM to wake it up
4. Your phone connects back to the relay over a separate authenticated channel
5. The relay splices the two connections together -- Claude's bytes go straight to your phone, and your phone's bytes go straight to Claude
6. The relay never decrypts, never inspects, never stores. It's a dumb pipe with a doorbell.

---

## How it works

### The relay is blind

The relay uses SNI passthrough. When Claude initiates a TLS connection, the relay peeks at the first few bytes to extract the hostname (this is how TLS works -- the hostname is sent unencrypted so the server knows which certificate to present). The relay uses this to route the connection to your phone, then gets out of the way. It forwards the raw encrypted bytes in both directions without ever terminating the TLS session.

This is the same technique used by CDNs and load balancers that need to route TLS traffic without reading it. It's not novel or exotic -- it's well-understood infrastructure.

### End-to-end TLS

Your phone is the TLS server. When the relay opens a new stream to your phone, it sends the raw TLS ClientHello from the AI client. Your phone performs the TLS handshake directly with the AI client, using a certificate issued by Let's Encrypt for your device's hostname. The relay is just forwarding bytes -- it doesn't have your private key, and it can't impersonate your phone.

The private key for your device's TLS certificate is generated in the Android Keystore (hardware-backed where available). It never leaves the secure element.

### OAuth on-device

Every AI client must be authorized before it can access anything. When Claude first connects to your health integration, your phone shows a notification: "Claude wants to access Health Connect." You open the app, see a code, and approve it. Only then does the client get a token.

This is a standard OAuth 2.1 device authorization flow (RFC 8628) with PKCE. The OAuth server runs entirely on your phone inside the TLS tunnel. The relay has no involvement in authorization.

Tokens are per-integration. Approving Claude for health data does not give it access to your notifications. Each integration is an independent OAuth server with its own tokens, its own approval flow, and its own revocation.

### Per-integration URLs

Each integration you enable gets its own secret hostname:

- Health: `https://brave-health.cool-penguin.rousecontext.com/mcp`
- Notifications: `https://falcon-notifs.cool-penguin.rousecontext.com/mcp`

The first part (`brave-health`, `falcon-notifs`) is a per-integration secret. The second part (`cool-penguin`) is your device identity. You share each URL only with the AI clients you want to have access.

If you only share your health URL with Claude, Claude can't discover or access your notifications integration -- it doesn't know the hostname, and it can't guess it (roughly 4 million combinations per integration).

### FCM wake

Your phone doesn't maintain a persistent connection. When an AI client connects, the relay sends a Firebase Cloud Messaging push to wake your phone. Your phone starts a foreground service, connects back to the relay, serves the request, and goes back to sleep when the client disconnects.

This means the app uses essentially zero battery when idle. The tradeoff is a few seconds of cold-start latency on the first connection.

---

## Trust model

### What you trust

- **Google FCM**: We use Firebase Cloud Messaging to wake your phone. Google can see that a wake event was sent to your device, but the FCM payload contains only `{"type": "wake"}` -- no data about what the AI client wants or which integration is being accessed.

- **Let's Encrypt**: Your device's TLS certificate is issued by Let's Encrypt via ACME DNS-01 challenges. You're trusting Let's Encrypt not to fraudulently issue a certificate for your hostname to someone else. This is the same trust model as every HTTPS website. We reinforce this with CAA records (only Let's Encrypt can issue for `rousecontext.com`) and Certificate Transparency monitoring (described below).

- **Your phone's OS**: The app reads data from on-device APIs (Health Connect, notification listener, etc.). You're trusting Android's permission model and the security of your device.

- **The relay operator (minimally)**: The relay can see connection metadata -- when connections happen, which device hostnames are accessed, source IPs, and connection durations. It cannot see the content of any request or response. A compromised relay could refuse to route traffic (denial of service) but cannot read or modify your data in transit.

### What you don't have to trust

- **The relay operator (for data confidentiality)**: Even if the relay is fully compromised, the attacker sees only encrypted bytes flowing between the AI client and your phone. They'd need your phone's private key (in the Android Keystore) to decrypt anything.

- **The network**: Standard TLS protections apply. Coffee shop Wi-Fi, hotel networks, whatever -- the data is encrypted end-to-end.

- **Other users**: Each device has its own subdomain, its own certificate, and its own mTLS credential to the relay. There is no shared state between devices.

### What AI clients can and can't do

**Can do** (once authorized for a specific integration):
- Call any MCP tool exposed by that integration
- Make requests whenever they want (your phone wakes up to serve them)

**Can't do:**
- Access integrations they weren't authorized for (separate tokens per integration)
- Access your phone when you've disabled an integration (returns 404)
- Act without an audit trail (every tool call is logged locally)
- Retain data beyond their own session (we don't control what the AI client does with data once received, but we don't store anything remotely)

---

## Deeper dive

### Certificate architecture

There are two layers of TLS and two kinds of certificates:

**Inner TLS (client to device, end-to-end):**
- Wildcard certificate: `*.cool-penguin.rousecontext.com`, issued by Let's Encrypt via ACME DNS-01
- Private key: ECDSA P-256, generated in Android Keystore (hardware-backed), never exportable
- Used by your phone as a TLS server certificate when AI clients connect
- Renewed automatically every 90 days via the relay's ACME orchestration

**Outer TLS (device to relay, mTLS):**
- Client certificate signed by the relay's own private CA ("Rouse Relay Device CA")
- Contains the device's own public key (extracted from the registration CSR) -- the device's private key never leaves the device
- The relay's mTLS verifier only trusts certs signed by this CA, so only registered devices can connect
- Also renewed every 90 days

The key insight: the relay signs the mTLS client cert but the device generates and holds the private key. The relay never has access to the device's private key for either certificate layer.

**Proactive renewal:**
- 14 days before expiry: device-side WorkManager job attempts silent renewal
- 7 days before expiry: relay sends an FCM nudge if the first attempt failed
- If the cert expires: the device falls back to Firebase token + signature authentication to prove identity and renew

### Secret URL scheme

Each device gets a stable subdomain (e.g., `cool-penguin`) assigned at registration. Each integration gets an independent secret prefix (e.g., `brave-health`), drawn from approximately 4 million adjective-noun combinations.

**Why this matters:**
- Knowing a device's subdomain exists is useless without the integration secret. The relay validates the secret against the stored value *before* sending an FCM wake. Invalid secrets result in a silent TCP close -- no wake, no battery drain, no indication the device exists.
- Each integration has a different secret, so compromising one URL doesn't reveal others.
- Secrets can be rotated instantly without reissuing certificates (the wildcard cert covers all prefixes).
- There's no timing oracle -- the relay rejects invalid secrets at the same point in the code path regardless of whether the device exists.

### OAuth 2.1 with PKCE

We support two OAuth grant types:

**Device code flow** (RFC 8628): The primary flow. The AI client gets a device code and polls for approval while the user enters a short code on their phone. No redirect URLs, no browser needed.

**Authorization code flow with PKCE**: For clients that support it. Uses S256 code challenges (RFC 7636). No client secrets -- PKCE replaces the need for them.

**Token lifecycle:**
- Access tokens expire after 1 hour
- Refresh tokens expire after 30 days, with rotation (each use issues a new refresh token and invalidates the old one)
- All tokens are scoped to a single integration
- All tokens for all integrations are revoked on subdomain rotation
- Tokens are stored as hashes on-device (not plaintext)

### Audit trail

Every MCP tool invocation is logged locally on your phone:

- Timestamp
- Tool name and arguments
- Response summary and duration
- Session ID (which connection triggered it)
- Integration ID

You can view the full audit history in the app, filter by integration or date range, and see exactly what each AI client asked for and got. Audit logs are retained for 30 days and then pruned.

Token grants are also logged -- you can see when each client was authorized and via which flow.

### Security monitoring

The app runs two background checks (every few hours via WorkManager):

**Self-cert verification:** The app connects to its own relay hostname and verifies the TLS certificate fingerprint matches the cert it provisioned. If someone swapped the cert (relay compromise, MITM), the app surfaces an alert. During the 90-day renewal window, both old and new fingerprints are accepted to avoid false positives.

**Certificate Transparency monitoring:** The app queries CT logs (via crt.sh) for any certificate issued for its subdomain. If a cert appears that the app didn't provision, it alerts you. This catches sophisticated attacks where an adversary might filter the self-check traffic but can't suppress a fraudulent cert from public CT logs.

### Rate limiting and bot protection

Multiple layers, applied at different points:

| Layer | Scope | Limit |
|---|---|---|
| Secret URL validation | Per connection | Invalid secret = silent TCP close, no FCM |
| FCM wake throttle | Per subdomain | One FCM push per cooldown window |
| `/wake` endpoint | Per subdomain | 6 requests per minute (token bucket) |
| Connection rate limit | Per (source IP, subdomain) | Sliding window counter, rejects scanners |
| OAuth endpoints | Per endpoint (global) | Sliding window, prevents brute force |
| MCP endpoint | Per endpoint (global) | Sliding window on authenticated requests |
| ACME issuance | Global | 50 certs per week (Let's Encrypt limit, tracked by relay) |
| Subdomain rotation | Per device | Once per 30 days |

The device-side rate limiters are global (not per-IP) because all traffic arrives through the relay. This is a known limitation -- a future improvement would be passing client IP metadata through the mux protocol.

---

## Known limitations and future work

We want to be upfront about what isn't perfect yet.

### Current limitations

- **No per-query consent**: Once you authorize an AI client for an integration, it can call any tool in that integration at any time. We log everything, but you can't approve individual queries. This is the biggest gap in the current model. See "future work" below.

- **FCM metadata visibility**: Google can see that wake events are sent to your device. The payloads are minimal (`{"type": "wake"}`), but the timing of wake events could theoretically be correlated with AI client activity.

- **Certificate pinning skipped**: We don't pin the relay's TLS certificate on the device. If we did, a Let's Encrypt cert rotation (every 90 days) would brick all devices until they updated the app. Without an auto-update channel, this tradeoff is necessary. The mTLS client cert already authenticates device-to-relay connections, and the system CA store validates the relay's cert.

- **Relay is a single point of failure**: If the relay goes down, no connections work. The relay is stateless (all persistent state is in Firestore), so recovery is fast, but there's currently no multi-relay failover.

- **AI client data retention is out of scope**: Once the AI client receives your data through an MCP session, we have no control over what it does with it. That's between you and the AI provider.

- **Token expiry is relatively recent**: Access tokens now expire after 1 hour with 30-day refresh tokens. Older builds had non-expiring tokens. If you're on an older build, update.

### Planned improvements

- **Per-query consent mode**: An opt-in mode where each tool call triggers a phone notification for approval before the response is sent. High friction but maximum control. Useful for sensitive integrations.

- **CT log monitoring improvements**: The current implementation queries crt.sh periodically. We'd like to add real-time monitoring via a CT log subscription service for faster detection.

- **Fallback CAs**: Adding Google Trust Services or ZeroSSL as fallback CAs for certificate issuance, in case Let's Encrypt has issues.

- **Multi-relay support**: Running multiple relay instances behind a load balancer for redundancy.

- **Per-integration secret rotation**: Currently all integration secrets rotate together. The schema supports independent rotation per integration -- just needs the UI and API work.

- **Client IP forwarding**: Passing the AI client's source IP through the mux protocol so device-side rate limiters can be per-IP instead of global.

---

## Security audit

We conducted a security audit on 2026-04-07. The findings are tracked in `docs/security-audit-2026-04-07.md` and the disposition of accepted risks is documented in `docs/security-decisions.md`. Critical findings (redirect URI validation, PKCE format validation, encryption at rest for sensitive data) were prioritized for immediate remediation.

If you find a security issue, please reach out. We'd rather hear about it than not.