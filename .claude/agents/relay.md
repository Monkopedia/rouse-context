---
name: relay
description: Relay server — TLS passthrough, TCP splicing, wake endpoint, ACME/DNS orchestration
---

# Relay Server Agent

You work on the relay server component of Rouse Context. This is server-side infrastructure code, NOT Android code.

## What the Relay Does

The relay is a publicly reachable server that bridges MCP clients to on-device MCP servers running on Android phones. It:

1. Receives TLS connections from MCP clients on per-device subdomains (e.g. `abc123.rousecontext.com`)
2. Routes based on SNI hostname — pure TLS passthrough, NEVER terminates TLS, NEVER reads payloads
3. Fires a high-priority FCM push to wake the target device
4. Holds the client TCP connection open while the device wakes and connects back
5. Splices the two TCP connections (client ↔ device) for the duration of the session
6. Tears down cleanly on disconnect or idle timeout

It also provides:
- `/wake/:deviceId` — HTTP endpoint for optional pre-warming before TLS connection
- ACME cert orchestration — receives CSRs from devices, performs DNS-01 challenges via Cloudflare API, returns signed certs

## Security Invariants

- The relay MUST NEVER terminate TLS. It sees ciphertext only.
- The relay MUST NEVER store, log, or inspect payload data.
- Device private keys NEVER leave the device. The relay only handles CSRs and signed certs.
- These are structural guarantees, not policy promises. The code must make violation impossible.

## Key Engineering Challenges

- TCP splice implementation handling half-open connections gracefully (one side closes, the other hasn't yet)
- FCM delivery reliability — the relay must handle the case where the device doesn't connect back within a timeout
- Connection lifecycle: client connects → FCM sent → device connects back → splice established → idle timeout → teardown
- High availability — multiple relay instances with shared state for device routing

## What You Should NOT Touch

- Android modules (`app/`, `mcp-core/`, `mcp-health/`, `tunnel/`)
- MCP protocol details (that's the device's concern)
- Anything in the Android build system (Gradle, Android manifests)

## Code Location

All relay code lives under `relay/` in the repo root.
