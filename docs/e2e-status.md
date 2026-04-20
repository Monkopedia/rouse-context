# End-to-End Status (2026-04-06)

## What works
- Device onboarding: Firebase auth → relay registration (two-round-trip) → ACME server cert + relay CA client cert
- Device connects to relay via mTLS WebSocket (`active_mux_connections: 1` confirmed)
- Session handler wired in TunnelForegroundService to receive incoming mux streams
- Relay deployed at relay.rousecontext.com with real services (FCM, Firebase Auth, ACME, DeviceCa)
- Debug test MCP integration available (echo, get_time, device_info tools)

## Current blocker: AI client → relay passthrough → device
When an AI client connects to `{subdomain}.rousecontext.com:443`, the TLS handshake hangs.
The relay has the device's mux session registered, but the passthrough doesn't route the client connection to it.

### What the relay logs show
```
INFO rouse_relay::api::ws: Device WebSocket upgrade accepted subdomain=main-board
INFO rouse_relay::api::ws: Mux session registered subdomain=main-board
INFO rouse_relay::api::ws: Mux session loop started subdomain=main-board
```
No log entries about the AI client connection arriving or being routed.

### What the integration tests do differently
`EndToEndSessionTest` scenario 5 manually pumps mux frames from the WebSocket listener — it doesn't use `TunnelClientImpl` at all. The test builds its own frame routing. This means:
1. The test proves the relay's passthrough works (OPEN frames are sent)
2. But it doesn't test whether `TunnelClientImpl.incomingSessions` actually emits
3. The production code path (`TunnelClientImpl` → `MuxDemux` → `incomingSessions` flow) may have a bug

### Investigation needed
1. Does `TunnelClientImpl.incomingSessions` actually work? Check if `MuxDemux.onOpen()` emits to the flow
2. Does the relay's passthrough handler (`passthrough.rs`) correctly look up the mux session by subdomain?
3. Does the relay's SNI router send the client to the passthrough handler when SNI matches a device subdomain?
4. Is there a mismatch between how `SessionRegistry` stores sessions (by `ws.rs`) and how `passthrough.rs` looks them up?

### Key file locations
- Relay passthrough: `relay/src/passthrough.rs`
- Relay session registry: `relay/src/passthrough.rs` (SessionRegistry)
- Relay WS handler: `relay/src/api/ws.rs` (registers mux session)
- Relay main connection handler: `relay/src/main.rs` (routes by SNI)
- Device tunnel client: `core/tunnel/src/commonMain/kotlin/com/rousecontext/tunnel/TunnelClientImpl.kt`
- Device mux demux: `core/tunnel/src/commonMain/kotlin/com/rousecontext/tunnel/MuxDemux.kt`
- Device session handler: `work/src/main/kotlin/com/rousecontext/work/SessionHandler.kt`
- Device MCP bridge: `app/src/main/java/com/rousecontext/app/session/McpSessionBridge.kt`
- Integration test: `core/tunnel/src/jvmTest/kotlin/com/rousecontext/tunnel/integration/EndToEndSessionTest.kt`

## Known issues to fix
1. **MtlsWebSocketFactory created at Koin init** — if onboarding happens after init, the factory has no cert. Needs lazy creation or recreation after onboarding.
2. **API 31+ foreground service restriction** — can't start foreground service from background broadcast. Need to use `setForegroundServiceBehavior()` or handle differently.
3. **Onboarding blocks dashboard** — should be a non-blocking banner, not a separate screen.
4. **ACME cert consumption** — each `pm clear` + re-onboard burns a Let's Encrypt cert (50/week limit). Be careful in testing.

## Device info
- Pixel 3 XL, API 31, package `com.rousecontext.debug`
- Wireless ADB: `<your-lan-ip>:<port>`
- Subdomain: `main-board`
- Relay: `relay.rousecontext.com` (35.209.161.222)
