# Rouse Context

Android app + relay server that turns a phone into a secure, on-demand MCP server.

## Architecture

Three distinct domains live in this repo:

- **Relay server** (`relay/`) — TLS passthrough via SNI routing, `/wake` endpoint, ACME cert orchestration, DNS-01 challenges via Cloudflare API. Never terminates TLS, never sees payloads.
- **Tunnel client** (`tunnel/`) — Android module. FCM wakeup receiver, TLS client connecting back to relay, session lifecycle, wakelock management. Bridges the relay protocol to on-device MCP.
- **Android app** (`app/`, `mcp-core/`, `mcp-health/`) — MCP SDK integration, Health Connect server, audit UI, foreground service. Pure Android/Kotlin.

## Build & Test

All Gradle commands require `JAVA_HOME=/usr/lib/jvm/java-17-openjdk`.

```bash
# Build library modules (app has a pre-existing missing icon issue)
./gradlew :mcp-core:assembleDebug :mcp-health:assembleDebug :tunnel:assembleDebug

# Unit tests
./gradlew :mcp-core:testDebugUnitTest

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

- `:mcp-core` — MUST NOT depend on Android framework APIs beyond what's in `androidx.core`. It's the contract layer.
- `:mcp-health` — MUST depend only on `:mcp-core` and Health Connect. No tunnel, no app.
- `:tunnel` — MUST NOT know about MCP. It provides raw I/O streams. Zero knowledge of what travels over them.
- `:app` — Wires everything together. The ONLY module that depends on all others.
- `relay/` — Completely independent. Rust. No Android dependencies. Built with Cargo, deployed as a static binary to a small VPS.

## Code Style

- ktlint with `android_studio` style (see `.editorconfig`)
- detekt with coroutine rules enabled (see `config/detekt/detekt.yml`)
- No wildcard imports
- Structured concurrency — no `GlobalScope`, no unscoped `CoroutineScope()`, no `runBlocking` in production code

## Domain: rousecontext.com

Registered at Squarespace, nameservers pointed to Cloudflare. Each device gets a unique subdomain (e.g. `abc123.rousecontext.com`). Cloudflare DNS API for automated ACME challenge TXT records.
