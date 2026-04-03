# Rouse Context

On-demand MCP (Model Context Protocol) server for Android.

## Overview

Rouse Context turns your Android device into an MCP server that AI assistants can
connect to on demand. Sessions are triggered via FCM push notifications — the device
wakes up, provisions a per-device ACME TLS certificate, and establishes an
end-to-end encrypted connection through an SNI passthrough relay. No persistent
cloud sync of user data ever occurs; all context stays on the device until
explicitly served over a live session.

## Architecture

| Module | Purpose |
|---|---|
| `:app` | Main application shell — UI, session history/audit log, trust notifications, settings |
| `:tunnel` | FCM wakeup, ACME cert provisioning, SNI passthrough relay client, session lifecycle, wakelock management |
| `:mcp-core` | Thin abstractions over the MCP Kotlin SDK — defines `McpServerProvider`, the bindable-service contract for MCP servers |
| `:mcp-health` | Health Connect MCP server implementation |

## Key properties

- **FCM-triggered** — no persistent connection; the device is woken only when a
  session is requested.
- **E2E encrypted** — TLS terminates on the device via per-device ACME
  certificates. The relay performs SNI passthrough only and never sees plaintext.
- **No cloud sync** — user data is read from on-device sources (Health Connect,
  etc.) and served live. Nothing is persisted in the cloud.
- **Extensible** — additional MCP servers can be added as new modules implementing
  `McpServerProvider`, or installed as third-party apps binding to the same
  service protocol.

## Building

```bash
./gradlew assembleDebug
```

Requires Android SDK with API 35 and JDK 17+.

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.
