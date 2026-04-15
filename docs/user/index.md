---
title: Home
layout: default
nav_order: 1
---

# Rouse Context

Your phone already knows a lot about you — your step count, your notifications, which apps you use. Rouse Context lets an AI assistant ask your phone for that context, on demand, without ever uploading it to a cloud service.

It is an Android app that turns your phone into a private server that AI clients (Claude, Cursor, and similar) can connect to. When a client connects, your phone wakes up just long enough to answer, then goes back to sleep.

## What is it for?

Rouse Context is for people who want AI assistants to use context from their phone — "how many hours did I sleep last night?", "summarize my missed notifications", "how much time did I spend in Slack this week?" — without handing that data to a third-party sync service.

It is not for:

- Continuously streaming data to the cloud. There is no cloud store.
- Sharing data between multiple users or devices. Each phone stands alone.
- Replacing a full smart-home hub or calendar service. It is a bridge between your phone and an AI client.

## How does it work?

You install the app, pick the integrations you want (Health Connect, Notifications, Usage Stats, and Outreach are available today), and the app gives each one a secret URL that looks like `https://brave-health.cool-penguin.rousecontext.com/mcp`.

You paste that URL into your AI client. When the AI wants to use it, the flow looks like this:

1. The AI client connects to the URL.
2. A small relay server in the middle sees the connection request and sends a push notification to wake your phone.
3. Your phone wakes up, connects back through the relay, and the relay splices the two encrypted streams together.
4. The AI and your phone talk directly, end-to-end encrypted. The relay only forwards bytes — it cannot read them.
5. When the AI disconnects, your phone goes back to sleep.

The first time a new AI client tries to use an integration, you have to approve it on the phone (like pairing). After that it can call the tools that integration exposes until you revoke the approval.

## Where to next

- **[Security](security.md)** — what the alerts mean and how the encryption works.
- **[Privacy](privacy.md)** — what data leaves the device, and what the relay can and cannot see.
- **[Integrations](integrations.md)** — an overview of each integration and how to set it up.
- **[Troubleshooting](troubleshooting.md)** — common problems and how to fix them.
- **[FAQ](faq.md)** — the questions people ask most often.
