---
name: android
description: Android app modules — MCP SDK, Health Connect, audit UI, app shell
---

# Android App Agent

You work on the pure Android/Kotlin modules: `:mcp-core`, `:mcp-health`, and `:app`. These handle MCP server logic, health data exposure, and the user-facing app.

## Modules

### `:mcp-core`
The MCP contract layer. Defines `McpServerProvider` (interface for registering tools/resources on the SDK Server), `McpSession` (session orchestrator over raw I/O streams), and `AuditListener` (callback for tool-call logging).

- Built on the official MCP Kotlin SDK (`io.modelcontextprotocol:kotlin-sdk`)
- Providers use SDK types directly (`server.addTool()`, `server.addResource()`)
- `McpSession.run(input, output)` suspends until session ends
- MUST NOT depend on Android framework APIs beyond `androidx.core`

### `:mcp-health`
Health Connect MCP server. Implements `McpServerProvider` to expose live health data (steps, heart rate, sleep, HRV, workouts) as MCP tools and resources.

- Depends only on `:mcp-core` and `androidx.health.connect`
- Registers tools/resources in `register(server: Server)`
- Health Connect queries run on the calling coroutine context

### `:app`
The shell that wires everything together:

- Foreground service during active MCP sessions
- Per-tool-call audit log (implements `AuditListener`)
- Session history UI
- Trust/permission management
- Settings
- Creates `McpSession(providers, auditListener)` and connects it to tunnel streams

## Key Patterns

- MCP SDK types used directly — no wrapper types unless there's a clear value add
- `McpSession` uses `CompletableDeferred` + `server.onClose` to suspend until session ends
- Tests use loopback sockets with SDK `Client` ↔ `StdioServerTransport` for end-to-end verification

## What You Should NOT Touch

- `relay/` — server-side code, different language/domain
- `tunnel/` — networking layer, different concerns (FCM, TLS, wakelocks)
- You CAN read tunnel code to understand the stream interface, but don't modify it

## Build & Test

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mcp-core:assembleDebug :mcp-health:assembleDebug
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mcp-core:testDebugUnitTest
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew ktlintCheck detekt
```
