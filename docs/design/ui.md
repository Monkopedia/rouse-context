# UI Design

## Screen / Content / Destination split

Every UI route is registered as a `*Destination` composable in `app/src/main/java/com/rousecontext/app/ui/navigation/destinations/`. The destination owns its `ViewModel` and calls a `*Content(state, ...)` composable from `app/src/main/java/com/rousecontext/app/ui/screens/` to render. The `*Screen.kt` files in the same screens directory wrap `*Content` for previews and screenshot tests; production never goes through `*Screen`. When this doc says "the Settings screen", read that as `SettingsDestination` → `SettingsContent` (with `SettingsScreen` only used for previews/tests). The `WelcomeScreen` and `NotificationPreferencesScreen` wrappers are the two exceptions that are still called from production destinations.

## Screens

1. Welcome (first launch only)
2. Notification Preferences (post-session mode picker, runs during onboarding)
3. Main Dashboard (Home)
4. Add Integration Picker
5. Integration Setup: Setting Up (cert/wiring spinner; cert provisioning normally already ran during onboarding)
6. Integration Setup — Health Connect (`health_connect_setup/{mode}`)
7. Integration Setup — Notifications (`notification_setup/{mode}`)
8. Integration Setup — Outreach (`outreach_setup/{mode}`)
9. Integration Setup — Usage (`usage_setup/{mode}`)
10. Integration Enabled (URL + waiting for client)
11. Integration Manage (app-owned: URL, recent activity, authorized clients, disable)
12. All Clients (per-integration full list of authorized clients)
13. Authorization Approval (full-screen device-code approve/deny)
14. Audit History
15. Audit Detail (single audit entry)
16. Settings

## Integration States

Derived from `IntegrationStateStore` (user toggle) + `TokenStore` (client authorized):

| State | Where it shows | Derived from |
|---|---|---|
| Available | Add picker | `!userEnabled`, never set up |
| Disabled | Add picker | `!userEnabled`, was previously set up. Re-enable may skip setup. |
| Pending | Dashboard | `userEnabled`, `!tokenStore.hasTokens(id)` |
| Active | Dashboard | `userEnabled`, `tokenStore.hasTokens(id)` |
| Unavailable | Add picker (greyed) | `!isAvailable()`, platform not present |

State transitions:
```
Available ──[setup flow]──→ Pending ──[client authorizes for this integration]──→ Active
Disabled  ──[re-enable]───→ Pending ──[client authorizes]──→ Active
Active    ──[user disables]──→ Disabled (back in Add picker)
Pending   ──[user disables]──→ Disabled (back in Add picker)
Active    ──[all tokens for this integration revoked]──→ Pending
```

## Onboarding State (device-level)

Observed by `OnboardingViewModel` and `MainDashboardViewModel`. Source of truth: `app/src/main/java/com/rousecontext/app/ui/viewmodels/OnboardingViewModel.kt:21-43`.

```kotlin
sealed interface OnboardingState {
    data object Checking : OnboardingState
    data object NotOnboarded : OnboardingState
    data class InProgress(val step: OnboardingStep) : OnboardingState
    data object Onboarded : OnboardingState
    data class Failed(val message: String) : OnboardingState
    data class RateLimited(val retryDate: String) : OnboardingState
}

enum class OnboardingStep {
    Registering,        // POST /request-subdomain + POST /register
    ProvisioningCerts,  // POST /register/certs (ACME hop)
}
```

Only two `OnboardingStep` values exist — there is no separate "generating keys" UI step (key generation runs inside `CertProvisioningFlow` while the UI is showing `ProvisioningCerts`). Renew / expired banners are surfaced via a separate state machine driven by `CertRenewalWorker` rather than this enum.

## Screen Wireframes

### Welcome

```
┌─────────────────────────────────────┐
│           WELCOME                    │
│                                      │
│   [Rouse Context logo/icon]          │
│                                      │
│   Turn your phone into a secure      │
│   AI context server. Your data       │
│   stays on your device — AI          │
│   clients connect through an         │
│   encrypted tunnel.                  │
│                                      │
│                                      │
│         [ Get Started ]              │
│                                      │
└──────────────────────────────────────┘
```

Shown once on first launch. Navigates to Main Dashboard.

### Main Dashboard

Default state (no integrations yet):
```
┌─────────────────────────────────────┐
│                                      │
│  ○ Disconnected                      │
│                                      │
│  Integrations                        │
│  ┌─────────────────────────────────┐ │
│  │ No integrations enabled yet.    │ │
│  │        [ + Add your first ]     │ │
│  └─────────────────────────────────┘ │
│                                      │
│  Recent Activity                     │
│  (empty)                             │
│                                      │
│  ┌────┐  ┌────┐  ┌────┐              │
│  │Home│  │Audit│ │ Set│              │
│  └────┘  └────┘  └────┘              │
│  Home    Audit   Settings            │
└──────────────────────────────────────┘
```

With integrations:
```
┌─────────────────────────────────────┐
│                                      │
│  ● Connected (1 active session)      │
│                                      │
│  Integrations                [+ Add] │
│  ┌─────────────────────────────────┐ │
│  │ ● Health Connect       Active   │ │
│  │                         [Copy]  │ │
│  ├─────────────────────────────────┤ │
│  │ ◐ Notifications       Pending  │ │
│  │   Waiting for client    [Copy]  │ │
│  └─────────────────────────────────┘ │
│                                      │
│  Recent Activity                     │
│  10:32 AM  health/get_steps  142ms   │
│  10:31 AM  health/get_sleep  89ms    │
│  [View all →]                        │
│                                      │
└──────────────────────────────────────┘
```

[+ Add] only visible when unconfigured integrations exist. Tapping Pending integration goes to Integration Enabled waiting screen. Tapping Active integration goes to its settings. [Copy] copies the full integration URL.

### Main Dashboard — Cert Status Banners

Shown at top of dashboard when cert is not in Valid state:

```
(renewing — proactive, not urgent)
┌─────────────────────────────────────┐
│ ⟳ Refreshing your certificate...    │
│   Integrations may be briefly       │
│   unavailable.                      │
└─────────────────────────────────────┘

(expired, renewal in progress)
┌─────────────────────────────────────┐
│ ⚠ Certificate expired. Renewing... │
│   Integrations are offline until    │
│   this completes.                   │
└─────────────────────────────────────┘

(expired, renewal failing)
┌─────────────────────────────────────┐
│ ⚠ Certificate expired. Renewal     │
│   failed — check your connection.   │
│                          [Retry]    │
└─────────────────────────────────────┘

(rate limited)
┌─────────────────────────────────────┐
│ ⏳ Certificate issuance delayed.    │
│   Will retry automatically on       │
│   Apr 11.                           │
└─────────────────────────────────────┘

```

The "first-run registration in progress" banner is no longer shown on the dashboard: post-#389 the onboarding flow blocks until both `OnboardingStep.Registering` and `OnboardingStep.ProvisioningCerts` complete, so the user only reaches Home in `OnboardingState.Onboarded`. The two-step copy ("Registering" → "Provisioning certificates") lives on the onboarding destination instead. See `docs/design/android-app.md` "Device Onboarding" for the sequence.

### Add Integration Picker

```
┌─────────────────────────────────────┐
│     ADD INTEGRATION                  │
│                                      │
│  ┌─────────────────────────────────┐ │
│  │ Health Connect                  │ │
│  │ Share step count, heart rate,   │ │
│  │ and sleep data with AI clients  │ │
│  │                        [Set up] │ │
│  ├─────────────────────────────────┤ │
│  │ Notifications (coming soon)     │ │
│  │ Let AI clients read your        │ │
│  │ notifications                   │ │
│  │                        [Soon]   │ │
│  └─────────────────────────────────┘ │
│                                      │
│                         [ Cancel ]   │
│                                      │
└──────────────────────────────────────┘
```

Shows Available + Disabled integrations. Disabled ones show "Re-enable" instead of "Set up". Future/unavailable ones greyed out with "Soon" or "Not available on this device".

### Notification Preferences (onboarding step)

Part of the onboarding flow — sits between Welcome and the autostart re-entry. Not shown for subsequent integration adds. Route: `onboarding/notification_preferences`.

```
┌─────────────────────────────────────┐
│     NOTIFICATION PREFERENCES         │
│                                      │
│   How would you like to be           │
│   notified after AI sessions?        │
│                                      │
│   ● Summary (recommended)            │
│     "10 tool usages made"            │
│                                      │
│   ○ Each tool usage                  │
│     Individual notification per      │
│     data access                      │
│                                      │
│   ○ Suppress notifications           │
│     Only see activity in audit log   │
│                                      │
│                                      │
│   [ Continue ]                       │
│                                      │
└──────────────────────────────────────┘
```

If not "suppress": system notification permission dialog follows.

### Integration Setup: Integration-Specific

App-owned destinations, one per integration: `health_connect_setup/{mode}`, `notification_setup/{mode}`, `outreach_setup/{mode}`, `usage_setup/{mode}`. Each is registered in `:app`'s nav graph; integrations themselves do not own routes (the legacy `McpIntegration.onboardingRoute` field is unused by routing). Example for Health Connect:

```
┌─────────────────────────────────────┐
│     HEALTH CONNECT SETUP             │
│                                      │
│   Health Connect lets AI clients     │
│   read your health data to give      │
│   personalized responses.            │
│                                      │
│   We'll request access to:           │
│                                      │
│   • Daily step count                 │
│   • Heart rate readings              │
│   • Sleep sessions                   │
│                                      │
│   You can change these permissions   │
│   at any time in system settings.    │
│                                      │
│   Every access is logged in the      │
│   app's audit history.               │
│                                      │
│   [ Grant Health Access ]            │
│   [ Cancel ]                         │
│                                      │
└──────────────────────────────────────┘
```

If permissions already granted (re-enable flow), this screen is skipped — `OnboardingFlow` calls `onComplete` immediately.

### Integration Setup: Setting Up

Only shown when device cert is not yet valid (first integration, or cert expired/renewing).

```
(first time)
┌─────────────────────────────────────┐
│     SETTING UP                       │
│                                      │
│              ⟳                       │
│                                      │
│   We're issuing a secure             │
│   certificate for your device.       │
│                                      │
│   This usually takes about           │
│   30 seconds.                        │
│                                      │
│   We'll notify you when it's         │
│   ready.                             │
│                                      │
│                         [ Cancel ]   │
│                                      │
└──────────────────────────────────────┘

(cert refreshing)
┌─────────────────────────────────────┐
│     SETTING UP                       │
│                                      │
│              ⟳                       │
│                                      │
│   Your certificate is being          │
│   refreshed. This usually takes      │
│   about 30 seconds.                  │
│                                      │
│   We'll notify you when it's         │
│   ready.                             │
│                                      │
│                         [ Cancel ]   │
│                                      │
└──────────────────────────────────────┘

(rate limited)
┌─────────────────────────────────────┐
│     SETTING UP                       │
│                                      │
│              ⏳                      │
│                                      │
│   Certificate issuance is            │
│   temporarily delayed.               │
│                                      │
│   We'll notify you when it's         │
│   ready.                             │
│   Expected: Apr 11.                  │
│                                      │
│                         [ Cancel ]   │
│                                      │
└──────────────────────────────────────┘
```

Auto-advances when cert becomes valid. If user leaves (Cancel or background), integration stays in Pending state. User can return to this screen by tapping the Pending integration on the dashboard.

### Integration Enabled (URL + Waiting)

```
┌─────────────────────────────────────┐
│     HEALTH CONNECT READY             │
│                                      │
│   ✓ Health Connect is set up         │
│                                      │
│   Add this URL to your AI client:    │
│                                      │
│   ┌─────────────────────────────┐    │
│   │ https://brave-health        │    │
│   │ .cool-penguin               │    │
│   │ .rousecontext.com/mcp       │    │
│   │                      [Copy] │    │
│   └─────────────────────────────┘    │
│                                      │
│   The first time your AI client      │
│   connects, you'll be asked to       │
│   approve it with a code.            │
│                                      │
│   Waiting for connection...          │
│                                      │
│                         [ Cancel ]   │
│                                      │
└──────────────────────────────────────┘
```

If device code auth arrives while on this screen, auto-navigates to Authorization Approval. Cancel goes to dashboard (integration stays Pending, tappable to return here).

URL format: `https://<integration-secret>.<subdomain>.rousecontext.com/mcp` (per-integration hostname; the integration is identified by SNI, not by the path). The `<integration-secret>` is `{adjective}-{integrationId}` (e.g. `brave-health`, `swift-notifications`) — a fresh adjective is rolled for each integration on registration / rotate. Source: `app/src/main/java/com/rousecontext/app/UrlBuilder.kt:14-48` (`McpUrlProvider.buildUrl()` + `buildMcpUrl()`); see also `docs/design/relay-api.md` for the per-integration secret schema.

### Integration Manage (app-owned)

Shown when tapping an Active or Pending integration on the dashboard. App-owned destination at route `integration/{integrationId}` (`Routes.INTEGRATION_MANAGE`). Shows operational info and management actions.

For Active:

```
┌─────────────────────────────────────┐
│     HEALTH CONNECT          Active   │
│                                      │
│   ┌─────────────────────────────┐    │
│   │ https://brave-health        │    │
│   │ .cool-penguin               │    │
│   │ .rousecontext.com/mcp       │    │
│   │                      [Copy] │    │
│   └─────────────────────────────┘    │
│                                      │
│   Recent Activity                    │
│   10:32 AM  get_steps       142ms    │
│   10:31 AM  get_sleep        89ms    │
│   [View all →]                       │
│                                      │
│   Authorized Clients                 │
│   ┌─────────────────────────────┐    │
│   │ Claude Desktop              │    │
│   │ Apr 2 · 2 hours ago [Revoke]│    │
│   ├─────────────────────────────┤    │
│   │ Cursor                      │    │
│   │ Apr 3 · just now    [Revoke]│    │
│   └─────────────────────────────┘    │
│                                      │
│   [ Settings ]                       │
│   [ Disable Integration ]            │
│                                      │
└──────────────────────────────────────┘
```

For Pending (no clients yet):

```
┌─────────────────────────────────────┐
│     HEALTH CONNECT         Pending   │
│                                      │
│   ┌─────────────────────────────┐    │
│   │ https://brave-health        │    │
│   │ .cool-penguin               │    │
│   │ .rousecontext.com/mcp       │    │
│   │                      [Copy] │    │
│   └─────────────────────────────┘    │
│                                      │
│   Waiting for first client...        │
│   Add the URL above to your AI       │
│   client to get started.             │
│                                      │
│   Authorized Clients                 │
│   (none yet)                         │
│                                      │
│   [ Settings ]                       │
│   [ Disable Integration ]            │
│                                      │
└──────────────────────────────────────┘
```

**Disable** removes from dashboard, puts back in Add picker.
**Revoke** removes token, may transition Active → Pending if last token. Tapping the Authorized Clients header navigates to the per-integration All Clients list (`all_clients/{integrationId}`).

Tapping a **Pending** integration on the dashboard:
- If cert still issuing → Setting Up spinner
- If cert ready → Integration Manage (Pending variant above)

There is no integration-owned settings route. Permission management for Health Connect is delegated to the system Health Connect UI; the other integrations expose their per-integration configuration directly inside the manage screen above. The `settingsRoute` field on `McpIntegration` is a legacy field retained only for source-compatibility (see `docs/design/android-app.md`).

### Authorization Approval (full screen)

```
┌─────────────────────────────────────┐
│                                      │
│                                      │
│     APPROVE CONNECTION               │
│                                      │
│   An AI client wants to access       │
│   Health Connect.                    │
│                                      │
│   Enter the code shown by            │
│   the client:                        │
│                                      │
│       ┌──┐ ┌──┐ ┌──┐ ┌──┐          │
│       │  │ │  │ │  │ │  │  —        │
│       └──┘ └──┘ └──┘ └──┘          │
│       ┌──┐ ┌──┐ ┌──┐ ┌──┐          │
│       │  │ │  │ │  │ │  │          │
│       └──┘ └──┘ └──┘ └──┘          │
│                                      │
│     [ Approve ]    [ Deny ]          │
│                                      │
│                                      │
└──────────────────────────────────────┘
```

**How the user gets here:**
- If on the Integration Enabled (URL) screen when auth arrives → auto-navigates to this screen
- All other cases → notification posted ("A client wants to connect"), user taps notification to open this screen
- Multiple simultaneous auth requests → each posts its own notification, user approves them one at a time, first-come first-served

On Approve or Deny, the destination pops back to wherever the user came from (Integration Enabled, dashboard, etc.). There is no separate "Connection Confirmed" screen.

### Audit History

```
┌─────────────────────────────────────┐
│     AUDIT HISTORY                    │
│                                      │
│  Filter: [All providers ▼] [Today ▼] │
│                                      │
│  Today                               │
│  ┌─────────────────────────────────┐ │
│  │ 10:32 AM  health/get_steps     │ │
│  │ 142ms  args: {days: 7}         │ │
│  ├─────────────────────────────────┤ │
│  │ 10:31 AM  health/get_sleep     │ │
│  │ 89ms   args: {days: 1}         │ │
│  ├─────────────────────────────────┤ │
│  │ 10:31 AM  health/get_heart_rate│ │
│  │ 201ms  args: {days: 7}         │ │
│  └─────────────────────────────────┘ │
│                                      │
│  Yesterday                           │
│  ┌─────────────────────────────────┐ │
│  │ 3:15 PM  health/get_steps      │ │
│  │ 156ms  args: {days: 30}        │ │
│  └─────────────────────────────────┘ │
│                                      │
│  [Clear history]                     │
│                                      │
└──────────────────────────────────────┘
```

Deep-link: `audit?provider={provider}&scrollToCallId={callId}` pre-filters to a provider and scrolls to a specific call (from notification tap). The single-row `audit_detail/{entryId}` route shows the full args/result JSON for one call.

### Settings

```
┌─────────────────────────────────────┐
│     SETTINGS                         │
│                                      │
│  Connection                          │
│  ┌─────────────────────────────────┐ │
│  │ Idle timeout           [5 min ▼]│ │
│  │ Disable timeout          [ ]    │ │
│  │ (requires battery exemption)    │ │
│  └─────────────────────────────────┘ │
│                                      │
│  Notifications                       │
│  ┌─────────────────────────────────┐ │
│  │ After session:  [Summary ▼]     │ │
│  └─────────────────────────────────┘ │
│                                      │
│  Security                            │
│  ┌─────────────────────────────────┐ │
│  │ Generate new address            │ │
│  │ (once per 30 days)         [→]  │ │
│  └─────────────────────────────────┘ │
│                                      │
│  ┌─────────────────────────────────┐ │
│  │ ⚠ Battery optimization          │ │
│  │ Disable to ensure reliable      │ │
│  │ wake-ups.           [Fix this]  │ │
│  └─────────────────────────────────┘ │
│                                      │
│  About                               │
│  ┌─────────────────────────────────┐ │
│  │ Version 0.1.0                   │ │
│  │ Apache 2.0 License              │ │
│  └─────────────────────────────────┘ │
│                                      │
└──────────────────────────────────────┘
```

## Navigation Flow

Onboarding (fresh install, post-#389/#392 — see `docs/ux-decisions.md` 2026-04-24):

```
Welcome (onboarding)
    │
    └─ [Get Started] ──→ Notification Preferences (onboarding/notification_preferences)
                                │
                                └─ [Continue] ──→ onboarding?autostart=true
                                                       │  (re-enters the SAME onboarding
                                                       │   destination; OnboardingViewModel
                                                       │   runs Firebase auth → /request-subdomain
                                                       │   → /register → /register/certs)
                                                       │
                                                       └─ on success ──→ Home
```

The Welcome and NotificationPreferences screens both share a single `OnboardingViewModel` instance hosted by the onboarding destination — the #392 invariant. Cert provisioning runs *during* onboarding rather than at first integration add (#389), so by the time the user reaches Home the device cert chain already exists.

Steady state (after Home is reached):

```
Home (main dashboard)
    │
    ├─ [+ Add] ──→ Add Integration Picker
    │                  │
    │                  └─ [Set up] ──→ Integration Setup (integration_setup/{id}, brief cert/wiring spinner)
    │                                       │
    │                                       └──→ Integration-specific Setup (health_connect_setup/{mode},
    │                                              notification_setup/{mode}, outreach_setup/{mode}, usage_setup/{mode})
    │                                                  │
    │                                                  └──→ Integration Enabled (integration_enabled/{id}, URL + waiting)
    │                                                            │
    │                                                            └──→ Authorization Approval
    │                                                                  (auto-navigate ONLY from this screen)
    │                                                                         │
    │                                                                         └──→ pop back to caller
    │
    ├─ [Pending integration] ──→ Integration Manage (Pending) or Integration Setup spinner (if cert still issuing)
    │
    ├─ [Active integration] ──→ Integration Manage (URL, activity, clients, disable)
    │                                │
    │                                └─ [View all clients] ──→ All Clients (all_clients/{id})
    │
    ├─ bottom nav: Audit ──→ Audit History ──→ Audit Detail (audit_detail/{entryId})
    │
    ├─ bottom nav: Settings ──→ Settings
    │
    ├─ notification tap (auth) ──→ Authorization Approval
    │
    └─ notification tap (audit) ──→ Audit History (filtered + scrolled to call)
```

Authorization requests that arrive when the user is NOT on the Integration Enabled screen are surfaced via notification only. No forced navigation from other screens.

## ViewModels

All constructor-injectable via Koin. Accept interfaces for mockability. Unit tested. Provide preset states for screenshot tests.

| ViewModel | Observes | Key State |
|---|---|---|
| OnboardingViewModel | `CertificateStore.getSubdomain()`, Firebase auth, `OnboardingFlow`, `CertProvisioningFlow` | `OnboardingState` (`Checking` / `NotOnboarded` / `InProgress(step)` / `Onboarded` / `Failed` / `RateLimited`). Single shared instance owned by the onboarding destination — the post-#392 invariant for both Welcome and the autostart re-entry. |
| NotificationPreferencesViewModel | DataStore `post_session_notifications`, `POST_NOTIFICATIONS` permission state | selected mode, permission-denied flag, Continue enablement |
| MainDashboardViewModel | `TunnelClient.state`, onboarding state, enabled integrations, recent audit | connection indicator, integration list, activity preview, banners |
| AddIntegrationViewModel | available + disabled integrations | integration picker list |
| IntegrationSetupViewModel | cert/integration-secret readiness, setup flow step, pending authorization request | current step, progress, auto-navigate to authorization approval when on URL screen |
| HealthConnectSetupViewModel | Health Connect SDK availability + permissions | granted record types, permission grant flow, completion |
| NotificationSetupViewModel | NotificationListener access state | listener-grant flow, completion |
| OutreachSetupViewModel | `QUERY_ALL_PACKAGES` / launcher app list permission state | permission flow, completion |
| UsageSetupViewModel | `PACKAGE_USAGE_STATS` permission state | permission flow, completion |
| IntegrationManageViewModel | integration state, recent audit for this integration, tokens for this integration | URL, activity, authorized clients, disable, revoke |
| AuthorizationApprovalViewModel | pending device-code authorization request | user code input, validation, approve/deny (formerly named `DeviceCodeApprovalViewModel`) |
| AuditHistoryViewModel | audit Room queries | entries list, filters, empty state, scroll target |
| SettingsViewModel | DataStore preferences, battery optimization, rotation cooldown, security-check state | setting values, toggles, rotation availability, trust status |

## Testing

### ViewModel unit tests
- Each ViewModel tested with mock dependencies
- Verify state emissions for all input combinations
- Verify navigation events emitted correctly

### Screenshot/preview tests
- Each screen has preview composables with fake ViewModels in preset states
- Cover: empty state, loading, error, populated, edge cases (long text, many items)

### Integration setup flow tests
- Notification preferences shown during initial onboarding (between Welcome and the autostart re-entry); not shown for subsequent integrations
- Integration-specific onboarding skipped when permissions already granted
- Setting Up spinner only shown when cert / integration secrets not yet available (rare path post-#389 since onboarding provisions certs before Home)
- Auto-navigation from Integration Enabled to Authorization Approval on authorization arrival
- Cancel at any step leaves integration in correct state (Available or Pending)
- Re-enable disabled integration skips permissions if still granted
