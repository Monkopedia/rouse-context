---
title: Privacy
layout: default
nav_order: 3
---

# Privacy

This page explains what data leaves your phone, what the relay server sees, and what the app stores.

## What leaves your phone

Only what the AI client asks for, and only while the AI client is connected. There is no background upload, no analytics, no telemetry.

Concretely:

- When you enable an integration, the app reads data from the corresponding Android API (Health Connect, the notification listener, usage stats, and so on) only when an AI client calls a tool that needs it.
- The result is sent directly to the AI client through the encrypted tunnel. The relay forwards the encrypted bytes but cannot read them.
- When the AI disconnects, nothing further leaves the phone until the next connection.

If you revoke an AI client's approval from the Authorization screen, that client cannot ask for anything else until you approve it again.

## What the relay sees

The relay is deliberately minimal. It can see:

- That a connection was attempted for a given subdomain.
- The source IP of the AI client.
- The duration of the connection and the rough byte counts.

It cannot see:

- The content of any request or response.
- The OAuth approval flow (that happens inside the tunnel).
- Which specific integration tool was called.

Even if the relay were fully compromised, an attacker would see encrypted bytes and connection metadata, nothing more.

## What Google sees (FCM)

To wake your phone when it is asleep, the relay uses Firebase Cloud Messaging to send a push notification. The payload is literally `{"type": "wake"}` — no hostname, no integration name, no data. Google can tell that your device received a wake push at a particular time, but not what triggered it or what followed.

## What the app stores on the device

- **Audit log** — every tool call, its arguments, a short response summary, how long it took, and which AI client session it belonged to. Kept for 30 days, then pruned. You can review the full log in the Audit tab.
- **OAuth tokens** — stored as hashes, never in plaintext. Each AI client gets its own token per integration.
- **Integration configuration** — retention settings, permissions state, the integration-specific secret prefix.
- **TLS certificates** — the hardware-backed private key lives in the Android Keystore. The public certificate lives in app storage.

The app does not ship with any third-party SDKs that phone home. The only network calls are to the relay (for tunnel traffic and ACME cert orchestration) and to Firebase Cloud Messaging (for push wake-up).

## Deleting your data

Uninstalling the app clears all on-device state: audit log, tokens, integration config, certificate. There is nothing to delete remotely because nothing is stored remotely.

Rotating your subdomain from Settings generates a new address and invalidates all existing AI client approvals.

## See also

- [Security](security.md) — what the encryption guarantees.
- [FAQ](faq.md) — quick answers to common privacy questions.
