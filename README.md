# Rouse Context

Your phone has context that AI doesn't — your health data, your notifications, your app usage. Rouse Context makes that context available to AI assistants on demand, without ever syncing your data to the cloud.

It's an Android app that turns your phone into an [MCP](https://modelcontextprotocol.io/) server. AI clients like Claude connect to a URL, your phone wakes up, and a direct encrypted session is established. The AI asks for what it needs, your phone responds, and then it goes back to sleep. Your data never leaves your device except through that live session.

> **Status:** Active development. The core e2e flow works — Claude can connect, wake the phone, authorize via OAuth, and call MCP tools. Health Connect, Notifications, Outreach, and Usage Stats integrations are implemented. See `docs/design/` for detailed design documents.

## How It Works

```
AI Client ──TLS──> Relay (SNI passthrough) ──mux WebSocket (mTLS)──> Your Phone
```

1. You enable an integration (e.g. Health Connect) and get a URL like `https://brave-falcon.abc123.rousecontext.com/health/mcp`
2. Add that URL to Claude, Cursor, or any MCP client
3. When the client connects, the relay wakes your phone via FCM push
4. Your phone connects back through a mTLS WebSocket, and the relay splices the two TLS streams together
5. The AI client talks directly to your phone over end-to-end encrypted TLS — the relay never sees the plaintext

Sessions are ephemeral. The phone goes back to sleep when the client disconnects.

## Integrations

| Integration | What it exposes |
|---|---|
| **Health Connect** | Step count, heart rate, sleep, HRV, workout history |
| **Notifications** | Active and historical device notifications |
| **Outreach** | Send messages, make calls, toggle DND |
| **Usage Stats** | App usage patterns and screen time |

Each integration is independently enabled and gets its own MCP endpoint with OAuth authorization (PKCE).

## Architecture

### Android App

| Module | Purpose |
|---|---|
| `:app` | Compose UI, Koin DI, navigation, theming |
| `:core:tunnel` | Mux protocol, WebSocket client, TLS accept, CertificateStore |
| `:core:mcp` | MCP session routing, HTTP server, OAuth (device code + auth code + PKCE), token management |
| `:core:bridge` | Bridges tunnel mux streams to MCP sessions via TLS |
| `:api` | `McpIntegration` interface, `IntegrationStateStore` |
| `:integrations` | MCP providers: Health Connect, outreach (calls/SMS/DND), usage stats, notification capture |
| `:notifications` | Cross-cutting notification infrastructure, audit persistence (Room) |
| `:work` | Foreground service, FCM receiver, WorkManager |

### Relay Server (`relay/`)

Rust binary on a small VPS. Handles:
- **TLS passthrough** — routes AI client connections to devices via SNI, never terminates inner TLS
- **Mux WebSocket** — multiplexes multiple client sessions over one device connection
- **FCM wakeup** — sends push notifications to wake sleeping devices
- **ACME certs** — issues wildcard TLS certs per device via Let's Encrypt DNS-01 (Cloudflare API)
- **Bot protection** — secret URL prefix validated before waking device, plus FCM throttle and IP rate limiting

## Security

- **End-to-end encrypted** — TLS terminates on your phone. The relay does SNI passthrough only.
- **No cloud sync** — data is read from on-device sources and served live. Nothing stored remotely.
- **Per-device ACME certs** — wildcard certs via Let's Encrypt, private key in Android Keystore (hardware-backed).
- **mTLS device auth** — relay authenticates the device by client certificate before allowing connections.
- **Secret URL prefix** — each device URL includes a rotatable secret (`brave-falcon.abc123.rousecontext.com`). Bots that discover the device subdomain can't wake it without the secret.
- **OAuth per-client** — each AI client must be authorized via on-device approval before accessing tools.
- **Audit trail** — every tool invocation is logged locally with arguments, response, and duration.

## Building

### Android

```bash
./gradlew assembleDebug
```

Requires Android SDK (API 35) and JDK 17+.

### Coverage report

```bash
./gradlew koverHtmlReport
```

Aggregates line + branch coverage across every module's unit tests and the
`:core:tunnel:integrationTest` tier (real relay subprocess). HTML lands in
`build/reports/kover/html/index.html`. CI publishes the same report as a
`test-coverage` artifact plus a per-module summary on each PR.

### Relay

```bash
cd relay
cargo build --release
```

## Status

Active development. The core flow works end-to-end: Claude can connect, wake the phone, authorize via OAuth, and call MCP tools. See `docs/design/` for detailed design documents.

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.
