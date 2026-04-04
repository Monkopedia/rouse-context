---
name: tunnel-client
description: Android tunnel module — FCM wakeup, TLS client, relay connection, session lifecycle
---

# Tunnel Client Agent

You work on the `:tunnel` Android module. This module bridges the relay server to the on-device MCP session. It handles networking, wakeup, and session lifecycle but has ZERO knowledge of MCP.

## What the Tunnel Module Does

1. **FCM Wakeup** — Receives high-priority FCM messages that wake the device. Extracts the session/device ID from the push payload.
2. **TLS Connection** — Connects back to the relay server over TLS using the device's cert (stored in Android Keystore). The cert was provisioned during onboarding via ACME.
3. **Session Lifecycle** — Acquires a wakelock during active sessions. Provides raw `InputStream`/`OutputStream` to the app layer. Tears down cleanly on idle timeout, disconnect, or explicit close.
4. **ACME Cert Provisioning** — On first run (onboarding), generates a keypair in Android Keystore, creates a CSR, sends it to the backend, receives a signed cert for the device's subdomain.

## Module Boundaries

- `:tunnel` MUST NOT depend on `:mcp-core` or `:mcp-health`. It knows nothing about MCP.
- `:tunnel` exposes raw I/O streams. The `:app` module wires those streams to `McpSession`.
- `:tunnel` depends on: Firebase Messaging, AndroidX Lifecycle, ACME4J, AndroidX Core.

## Key Technical Details

- **Android Keystore** — Private key generated with `KeyPairGenerator` using `AndroidKeyStore` provider. Key is hardware-backed (HSM) on supported devices. The key NEVER leaves the secure element.
- **TLS** — The device acts as a TLS server (it has the cert for its subdomain), but initiates the TCP connection to the relay. The relay splices this connection to the waiting MCP client.
- **FCM** — High-priority messages target sub-500ms wakeup on non-Dozing devices. Must handle manufacturer-specific battery optimization (Samsung, Xiaomi).
- **Wakelocks** — Acquired when session starts, released on teardown. Use `PowerManager.PARTIAL_WAKE_LOCK`.

## What You Should NOT Touch

- `relay/` — server-side code
- `mcp-core/`, `mcp-health/` — MCP protocol layer
- `app/` UI code (but you may need to understand how app wires tunnel to MCP)

## Code Location

All tunnel code lives under `tunnel/src/` with package `com.rousecontext.tunnel`.

## Build & Test

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :tunnel:assembleDebug
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :tunnel:testDebugUnitTest
```
