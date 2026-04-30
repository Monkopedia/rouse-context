# Rouse Context

Your phone has context that AI doesn't — your health data, your notifications, your app usage. Rouse Context makes that context available to AI assistants on demand, without ever syncing your data to the cloud.

It's an Android app that turns your phone into an [MCP](https://modelcontextprotocol.io/) server. AI clients like Claude connect to a URL, your phone wakes up, and a direct encrypted session is established. The AI asks for what it needs, your phone responds, and then it goes back to sleep. Your data never leaves your device except through that live session.

> **Status:** v1.0 released. Available on [GitHub Releases](https://github.com/Monkopedia/rouse-context/releases). For Google Play beta access, email `bugs@rousecontext.com`. Works with Claude, Cursor, and any MCP-compatible client.

## How It Works

```
AI Client ──TLS──> Relay (SNI passthrough) ──mux WebSocket (mTLS)──> Your Phone
```

1. You enable an integration (e.g. Health Connect) and get a URL like `https://brave-health.abc123.rousecontext.com/mcp` (the integration is identified by the hostname prefix; the path is always `/mcp`)
2. Add that URL to Claude, Cursor, or any MCP client
3. When the client connects, the relay wakes your phone via FCM push
4. Your phone connects back through a mTLS WebSocket, and the relay splices the two TLS streams together
5. The AI client talks directly to your phone over end-to-end encrypted TLS — the relay never sees the plaintext

Sessions are ephemeral. The phone goes back to sleep when the client disconnects.

## Integrations

Each integration is independently enabled and gets its own MCP endpoint with OAuth authorization (PKCE). 22 tools across 4 integrations:

### Health Connect (3 tools)

Query Android Health Connect data across 36 record types in 7 categories (activity, body, sleep, vitals, nutrition, reproductive, mindfulness).

| Tool | Description |
|---|---|
| `list_record_types` | List Health Connect record types with permission status |
| `query_health_data` | Query records by type and time range |
| `get_health_summary` | Health summary across permitted types for a period |

### Notifications (5 tools)

Read, search, and interact with device notifications.

| Tool | Description |
|---|---|
| `list_active_notifications` | List posted notifications |
| `search_notification_history` | Search notification history by text, package, or time range |
| `get_notification_stats` | Notification counts, top apps, and busiest hour for a period |
| `perform_notification_action` | Invoke an action button on an active notification |
| `dismiss_notification` | Dismiss an active notification by key |

### Outreach (10 tools)

Let AI take actions on the device — launch apps, open links, send notifications, manage DND.

| Tool | Description |
|---|---|
| `launch_app` | Launch an installed app by package name |
| `open_link` | Open an http/https URL in the default app |
| `copy_to_clipboard` | Copy text to the clipboard |
| `send_notification` | Post a notification with optional action buttons and tap-to-open URL |
| `list_installed_apps` | List installed apps |
| `create_notification_channel` | Create a notification channel |
| `list_notification_channels` | List AI-created notification channels |
| `delete_notification_channel` | Delete an AI-created notification channel |
| `get_dnd_state` | Get Do Not Disturb state |
| `set_dnd_state` | Set Do Not Disturb state |

### Usage Stats (4 tools)

App usage patterns and screen time analytics.

| Tool | Description |
|---|---|
| `get_usage_summary` | Screen time totals and top apps for a period |
| `get_app_usage` | Per-day usage for one app |
| `get_usage_events` | Raw app foreground/background events over a range |
| `compare_usage` | Compare screen time between two periods; biggest deltas first |

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
| `:core:testfixtures` | Shared fixtures for integration-tier tests that boot the real relay binary |

### Relay Server (`relay/`)

Rust binary on a small VPS. Handles:
- **TLS passthrough** — routes AI client connections to devices via SNI, never terminates inner TLS
- **Mux WebSocket** — multiplexes multiple client sessions over one device connection
- **FCM wakeup** — sends push notifications to wake sleeping devices
- **ACME certs** — issues per-device TLS certs via Google Trust Services DNS-01 (Cloudflare API); Let's Encrypt also supported
- **Bot protection** — secret URL prefix validated before waking device, plus FCM throttle and IP rate limiting

## Security

- **End-to-end encrypted** — TLS terminates on your phone. The relay does SNI passthrough only.
- **No cloud sync** — data is read from on-device sources and served live. Nothing stored remotely.
- **Per-device ACME certs** — per-device certs via Google Trust Services (DNS-01), private key in Android Keystore (hardware-backed).
- **mTLS device auth** — relay authenticates the device by client certificate before allowing connections.
- **Secret URL prefix** — each device URL includes a rotatable per-integration secret (`brave-health.abc123.rousecontext.com`, where `brave-health` is `{adjective}-{integrationId}`). Bots that discover the device subdomain can't wake it without the secret.
- **OAuth per-client** — each AI client must be authorized via on-device approval before accessing tools.
- **Audit trail** — every tool invocation is logged locally with arguments, response, and duration.

## Building

### Android

```bash
./gradlew assembleDebug
```

Requires Android SDK (`compileSdk` 36, `targetSdk` 35, `minSdk` 24). Build requires JDK 21 (`JAVA_HOME=/usr/lib/jvm/java-21-openjdk`).

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

### End-to-end tests

The e2e tests drive a real device over `adb` via a (typically LAN-local) host
running `adb`. The `adb.host` system property is required and defaults to empty
— the `:e2e:e2eTest` task fails fast if unset. Device serial is optional and
only needed if multiple devices are connected to that host.

```bash
./gradlew :e2e:e2eTest \
    -Dadb.host=<your-dev-host> \
    -Dadb.serial=<your-device-serial>
```

The `:device-tests` module has a separate runner that builds an APK pointed at a
locally-running relay. It also requires a LAN IP reachable from the device:

```bash
./gradlew :device-tests:test -Dlan.ip=<your-lan-ip>
```

Without `lan.ip`, the device-tests skip cleanly via JUnit assumptions.

## Status

v1.0 released. 22 tools across 4 integrations, cold-start wake in ~3 seconds, end-to-end encrypted. See `docs/design/` for detailed design documents and [privacy policy](https://rousecontext.com/privacy).

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.
