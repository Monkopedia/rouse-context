# End-to-End Status (2026-04-21)

## What works

- Device onboarding: Firebase auth → relay registration (two-round-trip) → ACME server cert + relay CA client cert. As of #389 all three PEMs land during onboarding itself rather than being deferred until the user enables their first integration, closing the half-configured-fresh-install gap.
- Device connects to relay via mTLS WebSocket; subsequent reconnects use the client cert persisted at onboarding time.
- `TunnelClientImpl.incomingSessions` emits and `SessionHandler` receives mux streams; the AI client → relay passthrough → device path works end-to-end with a fresh install.
- Relay deployed at relay.rousecontext.com with real services (FCM, Firebase Auth, ACME, DeviceCa).
- Debug test MCP integration available (echo, get_time, device_info tools).

## History: the AI-client TLS EOF blocker (2026-04 series)

The April 6 snapshot of this doc listed "AI client → relay passthrough → device" as the open blocker. The actual root cause was a missing hop during onboarding — `OnboardingFlow.execute` stopped after `/register` and never called `/register/certs`, so a fresh install with no enabled integration had `rouse_subdomain.txt` but no `rouse_cert.pem` / `rouse_client_cert.pem` / `rouse_relay_ca.pem`.

Without a server cert the device returned a null `SSLContext` from `CertStoreTlsCertProvider.serverSslContext()`, so every incoming TLS handshake EOF'd. Without a client cert the WS reconnect hit the relay's mTLS gate and got 401. The production wiring (`TunnelClientImpl`, `MuxDemux.onOpen → incomingSessions`, `SessionHandler`) was already correct; it just couldn't show its work on a fresh install.

Fixed in #389 by chaining `CertProvisioningFlow.execute(firebaseToken)` into `OnboardingFlow` as a final step, with explicit retryable error surfaces for ACME rate-limit / network / storage failures so onboarding never drops the user onto a silent half-configured Home screen.

Related: #386 (AI client TLS EOF) and #387 (WS reconnect 401) were both superseded by #389 — same root cause.

## Known follow-ups

1. **API 31+ foreground service restriction** — can't start foreground service from background broadcast. Need to use `setForegroundServiceBehavior()` or handle differently.
2. **ACME cert consumption** — each `pm clear` + re-onboard burns a Let's Encrypt cert (50/week limit). Be careful in testing.

## Device info (snapshot)

- Pixel 6 Pro ("adolin"), package `com.rousecontext.debug`
- Relay: `relay.rousecontext.com`
