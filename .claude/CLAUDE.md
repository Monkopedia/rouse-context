# Rouse Context

Android app + relay server that turns a phone into a secure, on-demand MCP server.

## Architecture

Three distinct domains live in this repo:

- **Relay server** (`relay/`) — TLS passthrough via SNI routing, `/wake` endpoint, ACME cert orchestration, DNS-01 challenges via Cloudflare API. Never terminates TLS, never sees payloads.
- **Tunnel client** (`core/tunnel/`) — Mux protocol, TLS client connecting back to relay, session lifecycle, CertificateStore interface. Bridges the relay protocol to on-device MCP.
- **Android app** (`app/`, `core/mcp/`, `health/`, `api/`, `notifications/`, `work/`) — MCP SDK integration, Health Connect server, audit UI, foreground service. Pure Android/Kotlin.

## Build & Test

All Gradle commands require `JAVA_HOME=/usr/lib/jvm/java-21-openjdk`.

```bash
# Build library modules
./gradlew :core:mcp:assembleDebug :core:tunnel:assembleDebug :health:assembleDebug

# Unit tests
./gradlew :core:mcp:testDebugUnitTest

# Lint & static analysis
./gradlew ktlintCheck detekt

# Auto-fix formatting
./gradlew ktlintFormat
```

## Workflow

MUST follow this sequence for any non-trivial feature:

1. **Design** — Enter plan mode. Explore the codebase, understand existing patterns, design the approach. Present the plan for approval. Do NOT write code yet.
2. **Approve** — Wait for explicit approval before proceeding.
3. **Test** — Write tests that define the expected behavior. Verify they fail.
4. **Implement** — Write the minimum code to pass the tests. Do not modify tests to match implementation.
5. **Verify** — Run `./gradlew testDebugUnitTest ktlintCheck detekt`. Fix any failures.
6. **Commit** — Stage specific files, write a clear commit message.

Skip to step 4 for trivial changes (typos, single-line fixes, obvious bugs).

## Module Boundaries

- `:core:mcp` — MCP session orchestrator, HTTP routing, OAuth device code flow, token management. Contract layer.
- `:core:tunnel` — Mux protocol, WebSocket client, TLS accept, CertificateStore interface. MUST NOT know about MCP.
- `:api` — `McpIntegration` interface, `IntegrationStateStore`. Contract for integration modules.
- `:health` — Health Connect MCP server. Depends on `:api` and `:core:mcp`.
- `:notifications` — Notification state machine, audit persistence (Room). Depends on `:core:tunnel` and `:core:mcp`.
- `:work` — Foreground service, FCM receiver, WorkManager, wakelock. Depends on `:core:tunnel` and `:notifications`.
- `:app` — Wires everything together via Koin. The ONLY module that depends on all others.
- `relay/` — Completely independent. Rust. No Android dependencies. Built with Cargo.

## Code Style

- ktlint with `android_studio` style (see `.editorconfig`)
- detekt with coroutine rules enabled (see `config/detekt/detekt.yml`)
- No wildcard imports
- Structured concurrency — no `GlobalScope`, no unscoped `CoroutineScope()`, no `runBlocking` in production code

## Signing Keystores

- Debug keystore: `.signing/debug.keystore`
- Release keystore: `.signing/release.keystore`
- Backups: `~/backups/rouse-context/`

NEVER regenerate these keystores. Regenerating causes signature mismatch, which forces an uninstall (losing all app data) and wastes ACME certificate quota on re-registration.

## Domain: rousecontext.com

Registered at Squarespace, nameservers pointed to Cloudflare. Each device gets a unique subdomain (e.g. `abc123.rousecontext.com`). Cloudflare DNS API for automated ACME challenge TXT records.
