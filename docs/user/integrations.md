---
title: Integrations
layout: default
nav_order: 4
---

# Integrations

Each integration is a separate MCP server running on your phone. You enable them one at a time, each gets its own secret URL, and each AI client has to be approved separately for each integration.

This page gives a plain-English summary of each one. For the specific tool names and parameters, open the integration card in the app.

## Health Connect

**What it reads:** Step count, heart rate, sleep, workouts, heart-rate variability, and other metrics exposed through Health Connect.

**What it does:** Read-only. It queries Health Connect for the time range the AI asks about, summarizes the result, and sends it back.

**Setup:**

1. Install Health Connect from the Play Store if your phone does not already have it.
2. In Rouse Context, tap the Health Connect integration card and approve the permissions you want to share.
3. The app will tell you if your phone does not have any data available for a given metric.

## Notifications

**What it reads:** Active notifications on your phone and a searchable history of past ones, including the app name, title, text, and category.

**What it does:** Can list current notifications, look up past ones by text or app, produce summary stats, and perform a notification's existing actions (reply, archive, and so on) if the AI asks. Notifications from Rouse Context itself are unmodifiable — the AI can see them but cannot dismiss them or invoke their actions, so a Security Alert can't be cleared on your behalf.

**Setup:**

1. Tap the Notifications integration card.
2. Android opens the "Notification access" settings page — grant access to Rouse Context.
3. Back in the app, pick a retention window (1 day, 1 week, 1 month, 3 months, forever). Older records are pruned automatically.

**Caveat:** Some phone vendors aggressively kill the notification listener service to save battery. If history gaps appear, check your phone's battery optimization settings and exempt Rouse Context.

## Outreach

**What it reads:** Nothing, in terms of private data. It only lists installed apps so the AI knows what it can launch.

**What it does:** Takes actions on your behalf — launches an app, opens a link in the browser, copies text to the clipboard, posts a notification to you, or (optionally) toggles Do Not Disturb.

**Setup:**

1. Tap the Outreach integration card. Basic actions work immediately.
2. Optional: enable "Allow Do Not Disturb control" to add the DND tools. This opens Android's DND access settings.

**Caveat:** Outreach can launch apps and open URLs. If you approve an AI client for Outreach, that client can open whatever app or URL it wants (subject to URL-scheme filtering — only `http` and `https` are allowed). Approve with that in mind.

## Usage Stats

**What it reads:** How much time you spend in each app, when you used each app, and rough usage patterns. Pulled from Android's `UsageStatsManager`.

**What it does:** Summaries like "top 5 apps today", per-app breakdowns, and period-over-period comparisons ("this week vs last week").

**Setup:**

1. Tap the Usage Stats integration card.
2. Android opens the "Usage access" settings page — grant access to Rouse Context.
3. No further configuration.

**Caveat:** Android only keeps the per-minute detail for a limited window (a few days to a few weeks depending on the phone). Older queries return aggregated data.

## Revoking access

Every approved AI client shows up in the Authorization screen. Tap one to see which integrations it is approved for and revoke any you want. Rotating your subdomain from Settings revokes everyone at once.
