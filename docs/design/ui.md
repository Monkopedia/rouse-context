# UI Design

## Screens

1. Welcome (first launch only)
2. Main Dashboard
3. Add Integration Picker
4. Integration Setup: Notification Preferences (first integration only)
5. Integration Setup: Integration-Specific (delegates to McpIntegration.onboardingRoute)
6. Integration Setup: Setting Up (cert issuance spinner)
7. Integration Setup: Integration Enabled (URL + waiting for client)
8. Integration Manage (app-owned: URL, recent activity, authorized clients, disable, settings link)
9. Integration Settings (integration-owned: permissions, domain-specific config)
10. Device Code Approval (full screen)
11. Connection Confirmed
12. Audit History
13. Settings

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

## Cert Status (device-level)

Observed by MainDashboardViewModel and IntegrationSetupViewModel only.

```kotlin
sealed interface CertStatus {
    data object None : CertStatus
    data class Onboarding(val step: OnboardingStep) : CertStatus
    data object Valid : CertStatus
    data object Renewing : CertStatus
    data class Expired(val renewalInProgress: Boolean) : CertStatus
    data class RateLimited(val retryAfter: Instant) : CertStatus
}

enum class OnboardingStep {
    GENERATING_KEYS,
    REGISTERING,
    ISSUING_CERT,
}
```

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

(first run, registration in progress)
┌─────────────────────────────────────┐
│ ⟳ Setting up your device...         │
│   Generating keys ✓                 │
│   Registering ✓                     │
│   Issuing certificate...            │
└─────────────────────────────────────┘
```

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

### Integration Setup: Notification Preferences

First integration only. Skipped on subsequent integrations.

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

Delegates to `McpIntegration.onboardingRoute`. For Health Connect:

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
│   │ https://brave-falcon.       │    │
│   │ rousecontext.com/health     │    │
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

If device code auth arrives while on this screen, auto-navigates to Device Code Approval. Cancel goes to dashboard (integration stays Pending, tappable to return here).

### Integration Manage (app-owned)

Shown when tapping an Active or Pending integration on the dashboard. App-owned screen at `integration/{id}/manage`. Shows operational info and management actions.

For Active:

```
┌─────────────────────────────────────┐
│     HEALTH CONNECT          Active   │
│                                      │
│   ┌─────────────────────────────┐    │
│   │ https://brave-falcon.       │    │
│   │ rousecontext.com/health     │    │
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
│   │ https://brave-falcon.       │    │
│   │ rousecontext.com/health     │    │
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

**Settings** navigates to `integration/{id}/settings` (integration-owned screen).
**Disable** removes from dashboard, puts back in Add picker.
**Revoke** removes token, may transition Active → Pending if last token.

### Integration Settings (integration-owned)

Reached via Settings button on the Manage screen. Integration owns this screen via `settingsRoute`. Example for Health Connect:

```
┌─────────────────────────────────────┐
│     HEALTH CONNECT SETTINGS          │
│                                      │
│   Permissions                        │
│   ┌─────────────────────────────┐    │
│   │ ✓ Steps                     │    │
│   │ ✓ Heart rate                │    │
│   │ ✓ Sleep                     │    │
│   │ ○ Workout history  [Grant]  │    │
│   │ ○ HRV              [Grant]  │    │
│   └─────────────────────────────┘    │
│                                      │
│   (integration-specific settings     │
│    go here — data type config, etc.) │
│                                      │
└──────────────────────────────────────┘
```

Tapping a **Pending** integration on the dashboard:
- If cert still issuing → Setting Up spinner
- If cert ready → Integration Manage (Pending variant above)

### Device Code Approval (full screen)

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

### Connection Confirmed

```
┌─────────────────────────────────────┐
│                                      │
│                                      │
│         ✓ Connected                  │
│                                      │
│   Your AI client is now              │
│   authorized.                        │
│                                      │
│                                      │
│       [ Back to Home ]               │
│                                      │
│                                      │
└──────────────────────────────────────┘
```

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

Deep-link: `/audit/{sessionId}` pre-filters to that session's entries (from notification tap).

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

```
Welcome ──→ Main Dashboard
                │
                ├── [+ Add] ──→ Add Integration Picker
                │                    │
                │                    └── [Set up] ──→ Notification Prefs (first time only)
                │                                         │
                │                                         └──→ Integration-Specific Setup
                │                                                   │
                │                                                   └──→ Setting Up (if cert needed)
                │                                                            │
                │                                                            └──→ Integration Enabled (URL + waiting)
                │                                                                      │
                │                                                                      └──→ Device Code (auto-navigate, ONLY from this screen)
                │                                                                               │
                │                                                                               └──→ Connected ──→ Dashboard
                │
                ├── [Pending integration] ──→ Integration Manage (Pending) or Setting Up (if cert issuing)
                │
                ├── [Active integration] ──→ Integration Manage (Active: URL, activity, clients, disable)
                │                                │
                │                                └── [Settings] ──→ Integration Settings (integration-owned)
                │
                ├── bottom nav: Audit ──→ Audit History
                │
                ├── bottom nav: Settings ──→ Settings
                │
                ├── notification tap (auth) ──→ Device Code Approval
                │
                └── notification tap (audit) ──→ Audit History (filtered by sessionId)
```

Device code auth requests that arrive when the user is NOT on the Integration Enabled screen are surfaced via notification only. No forced navigation from other screens.

## ViewModels

All constructor-injectable via Koin. Accept interfaces for mockability. Unit tested. Provide preset states for screenshot tests.

| ViewModel | Observes | Key State |
|---|---|---|
| MainDashboardViewModel | TunnelClient.state, cert status, enabled integrations, recent audit | connection indicator, integration list, activity preview, cert banner |
| AddIntegrationViewModel | available + disabled integrations | integration picker list |
| IntegrationSetupViewModel | cert status, setup flow step, pending device code | current step, progress, auto-navigate to device code when on URL screen |
| IntegrationManageViewModel | integration state, recent audit for this integration, tokens for this integration | URL, activity, authorized clients, disable/settings actions, revoke |
| DeviceCodeApprovalViewModel | pending device code request | user code input, validation, approve/deny |
| AuditHistoryViewModel | audit DB queries | entries list, filters, empty state |
| SettingsViewModel | DataStore preferences, battery optimization, rotation cooldown | setting values, toggles, rotation availability |

## Testing

### ViewModel unit tests
- Each ViewModel tested with mock dependencies
- Verify state emissions for all input combinations
- Verify navigation events emitted correctly

### Screenshot/preview tests
- Each screen has preview composables with fake ViewModels in preset states
- Cover: empty state, loading, error, populated, edge cases (long text, many items)

### Integration setup flow tests
- Notification prefs shown on first integration only
- Integration-specific onboarding skipped when permissions already granted
- Setting Up screen shown only when cert not valid
- Auto-navigation from Integration Enabled to Device Code on auth arrival
- Cancel at any step leaves integration in correct state (Available or Pending)
- Re-enable disabled integration skips permissions if still granted
