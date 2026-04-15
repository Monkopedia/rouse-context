---
title: Troubleshooting
layout: default
nav_order: 5
---

# Troubleshooting

Common problems and how to fix them.

## The AI client cannot connect

**If you see a connection timeout or "server not reachable" from the AI client side:**

1. Check the dashboard in the app. If the status banner says "connecting" or "disconnected", the phone has not yet established its tunnel. Give it ten to twenty seconds — the first connection after idle wakes the service from scratch.
2. If the banner says "connected" but the AI client still times out, double-check the URL. Each integration has its own hostname; pasting the health URL into a client that expects the notifications URL will fail.
3. If the phone dashboard never reaches "connected", check your network — the phone needs outbound HTTPS to `*.rousecontext.com` on port 443. Corporate or school networks sometimes block it.

## "Foreground service limit reached" notification

**What it is:** Android 15+ restricts how long an app can run a foreground service in a given window. On busy days — many AI connections in a short period — the app may hit the limit.

**What to do:**

- Short-term: dismiss the notification. The next wake cycle will start a new service when the limit resets.
- If it happens constantly, open your phone's app-info for Rouse Context and disable battery optimization (Settings → Apps → Rouse Context → Battery → Unrestricted). This avoids most FGS limit issues.

## Certificate renewal failures

Certificates are renewed every 90 days. The app starts trying 14 days before expiry and has several fallbacks, so failures are rare.

**Symptoms:** The dashboard shows "cert renewing" for more than a few hours, or you see a "cert expired" state.

**What to do:**

1. Make sure the phone has internet access and can reach `*.rousecontext.com`. The renewal flow uses the relay's ACME orchestration.
2. Check that the system clock is correct. TLS and ACME are both strict about clock skew.
3. Open the app and tap the dashboard's renew action if one is offered.
4. If it still fails, go to Settings → Rotate address. This reissues from scratch.

If a certificate expires because renewal has been failing for weeks, the app falls back to a Firebase-token-based re-registration. You may need to sign the app into your Google account again if prompted.

## Notifications are missing from history

The notification listener is known to be killed aggressively by some phone vendors. If you see gaps:

1. Open Android Settings → Apps → Rouse Context → Battery → set to Unrestricted.
2. Some OEMs (Xiaomi, Oppo, Huawei) have a separate "autostart" or "protected apps" list. Add Rouse Context.
3. Confirm Notification Access is still granted: Settings → Apps → Special access → Notification access.

## Permissions keep getting revoked

Some permissions (like Usage Access) get revoked when Android goes into "app hibernation" mode after long periods of not opening the app. Open the app regularly or disable hibernation for Rouse Context in the app's Battery settings.

## I rotated the subdomain and now the AI client is broken

Rotating the subdomain is the "reset everything" action. By design, it:

- Generates a new subdomain for your phone.
- Issues new per-integration URLs.
- Revokes every AI client approval.

You need to re-copy the new URLs into your AI clients and re-approve them. This is expected after a rotation.

## Still stuck?

Open an issue on the [GitHub repo](https://github.com/Monkopedia/rouse-context) with the exact notification text, a screenshot if possible, and what you were doing at the time.
