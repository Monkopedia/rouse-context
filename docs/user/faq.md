---
title: FAQ
layout: default
nav_order: 6
---

# Frequently asked questions

## Can you (the developer) read my data?

No. The relay server is designed so that it cannot decrypt the traffic between the AI client and your phone. It sees connection metadata — when, which device, how long — but not content. See [Privacy](privacy.md).

## Does the app upload my data to the cloud?

No. The only outbound connections are to the relay (for tunnel traffic and certificate renewal) and to Firebase Cloud Messaging (for wake-up pushes). The wake push payload is `{"type": "wake"}` and contains no personal data.

## Which AI clients does this work with?

Anything that speaks MCP over HTTPS. That includes Claude (Desktop, Code, claude.ai), ChatGPT, Cursor, Gemini, Microsoft Copilot, VS Code, Windsurf, and others. You give the client the per-integration URL and it connects when it needs data.

## What does the app cost?

The app itself is free and open source. The relay is currently hosted by the project. If you prefer to run your own relay, see the self-hosting notes in the repository.

## What happens when my phone is in a spotty network?

The AI client sees a connection failure until the phone can reach the relay. There is no queue or retry on the relay side. The next attempt from the AI client starts fresh.

## How long is data retained?

- **Audit log:** 30 days on-device, then pruned.
- **Notification history:** configurable per integration (1 day up to forever).
- **OAuth tokens:** access tokens expire in one hour, refresh tokens in 30 days.

Nothing is retained off the device.

## Can I run multiple phones?

Yes. Each phone gets its own subdomain, its own certificate, and its own FCM device. There is no account system — the phone is the identity.

## What if I lose my phone?

Because there is no account, there is no remote "kill" button today. Your options are:

1. Remotely wipe the phone through Google Find My Device (this removes the keystore and all app data).
2. From another device, file an issue asking us to blocklist the subdomain on the relay. The relay will refuse to forward connections for it.

Lost-phone handling is an area we want to improve.

## Why does it take a few seconds to respond the first time?

Because your phone is asleep. The relay has to send a wake push, the phone has to start the foreground service, connect back, and negotiate TLS. Subsequent calls within the same session are fast.

## Can I use this without the relay?

Not today. The relay solves two problems: waking a sleeping phone, and traversing networks that cannot reach residential IPs directly. A local-network-only mode is a reasonable thing to want, but it is not implemented.

## Where can I report a security issue?

Email `security@rousecontext.com` for anything sensitive. For general bug reports, please open a GitHub issue — it's the fastest path and keeps the discussion public. If you can't use GitHub, `bugs@rousecontext.com` works as a fallback. We would rather hear about a problem than read about it later.
