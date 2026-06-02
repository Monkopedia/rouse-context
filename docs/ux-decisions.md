# UX Decisions

Append-only log of user-facing flow decisions. Governed by [`.claude/rules/ux-changes.md`](../.claude/rules/ux-changes.md).

Every PR that changes UX flow, timing, permission prompts, or error surfaces adds an entry here. Include:
- **Decision** — the one-line outcome
- **Approved by** — the person + how (in-session, PR review, etc.)
- **Context** — what problem prompted the change
- **Alternatives considered** — the roads not taken and why
- **Trade-off accepted** — what we gave up to get the chosen behavior
- **Relevant** — linked issues, PRs, memories

Newest entries on top.

---

## 2026-06-02 — Wire the dormant Connection settings; "Fix this" opens battery-optimization settings

**Decision:** Make the previously-inert "Connection" settings cluster functional
(#453): the idle-timeout dropdown now persists and changes the real timeout, the
"Disable timeout" switch persists and means "no idle timer," the battery-
optimization warning shows based on real status (not always-on), and its "Fix
this" button launches the system battery-optimization settings screen
(`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`).

**Approved by:** Jason, in-session 2026-06-02 — declared #453 a bug (these
controls were always meant to work), approved rolling the whole cluster into one
fix ("you can roll this all into one fix on the existing ticket").

**Context:** The entire Connection settings group was built as UI but never wired
to the ViewModel/destination: `setIdleTimeout` discarded its argument, the
disable switch's callback was unwired and gated on a `batteryOptimizationExempt`
flag that was hardcoded `false`, the battery warning's `showBatteryWarning` was
hardcoded `true` (so every user saw a permanent, non-actionable warning), and the
"Fix this" button did nothing. This is restoring intended behavior of shipped
controls, not adding new surfaces — but it does newly *activate* a system screen,
hence this entry.

**Alternatives considered:**
- **Direct exemption dialog** (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) —
  one-tap allow/deny, but requires the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
  permission, which Google Play restricts to apps whose core function needs it.
  Rejected to avoid Play-policy risk; the settings-list route needs no permission.
- **Fix only the dropdown, defer the switch/warning** — smaller, but leaves the
  permanent false battery warning and a second dead control in place; the cluster
  is one tangled knot, so splitting it ships a still-broken screen.

**Trade-off accepted:** "Fix this" lands the user on the system battery-
optimization *list* (they pick the app) rather than a one-tap dialog — an extra
step, accepted in exchange for needing no Play-restricted permission. Also: with
the idle timeout disabled, the tunnel stays foregrounded until the 6h `dataSync`
FGS cap stops it (the #452 path) — the intended semantics of the control.

**Relevant:**
- Filed by the nightly code-health pass as #453; scope expanded in-session.
- Battery-opt status read shared via `BatteryOptimization` (lifted from
  `BugReportUriBuilder`). Idle timeout persisted in `AppStatePreferences`, read
  dynamically by `IdleTimeoutManager` each wake cycle.

---

## 2026-05-26 — Surface the FGS-budget notification on run-time timeout, not just at startup

**Decision:** When Android calls `Service.onTimeout()` because the `dataSync`
foreground-service budget is exhausted *while the service is running*,
`TunnelForegroundService` posts the existing `FgsLimitNotifier` notification and
stops cleanly — the same notification already shown when the budget is exhausted
at startup.

**Approved by:** Jason, in-session 2026-05-26 ("sure" — to implement the
onTimeout handler reusing FgsLimitNotifier).

**Context:** Crashes #450/#451 (`ForegroundServiceDidNotStopInTimeException`)
were the service being force-killed when the cumulative 6h/24h dataSync FGS cap
ran out mid-session. The start-time half of this was already handled
(`ForegroundServiceStartNotAllowedException` → notify + stopSelf); the run-time
half (`onTimeout`) was missing, so the budget exhausting mid-run produced a
silent crash with no user-facing explanation.

**Alternatives considered:**
- **Stop silently on timeout, no notification** — fixes the crash but the user
  gets no signal that wakes have paused until the 24h window frees budget;
  inconsistent with the start-time path, which does notify.
- **Distinct "ran out mid-session" copy** — more precise, but it's the same
  underlying condition (budget exhausted, wakes paused, retries next window), so
  a second message would be noise.

**Trade-off accepted:** The same notification can now appear at a second moment
in the lifecycle (mid-session, not only at wake start). Accepted because the
user-facing meaning is identical — "FGS budget spent, wakes paused, will resume
when the rolling window frees up" — and a shared id means the two paths replace
rather than stack in the shade.

**Relevant:**
- Crashes: #450 (FcmReceiver.startTunnelService frame), #451 (system-generated
  frame) — same root cause, one fix.
- Mirrors the existing start-time handler in `TunnelForegroundService.startForegroundSafely`.

---

## 2026-04-24 — Cert provisioning runs at Continue, not at first integration add

**Decision:** Keep the post-#389 flow where relay subdomain registration and ACME cert provisioning fire immediately after the user taps Continue on NotificationPreferences, before Home loads.

**Approved by:** Jason, in-session 2026-04-24 ("we can keep the current, particularly since we don't have cert limit issues anymore"). Retroactive approval; the original #389 landing predated this rule.

**Context:** Fresh installs were landing on a half-configured Home without the PEMs required to serve AI clients (#386 / #387). The root cause was that `OnboardingFlow.execute()` never called `CertProvisioningFlow.execute()` — that call only happened inside `IntegrationSetupViewModel`, so a user who onboarded and didn't add an integration was stuck.

**Alternatives considered:**
- **Defer to first integration add** — cert provisioning fires only when the user enables their first integration. Pros: no quota burn if the user bails; registration happens when there's actually something to register. Cons: first-integration setup is now several seconds slower (ACME hop); Home is reachable without certs.
- **Partial** — register subdomain at Continue (needed to display the device's address), defer certs to first integration. Adds a split state to reason about.
- **Block tunnel startup without certs** — foreground service refuses to start until certs exist. Makes the failure loud but doesn't fix the underlying gap.

**Trade-off accepted:** Every fresh install commits a subdomain + a cert even if the user bails before enabling an integration. Acceptable because the project is on Google Trust Services (no LE 50/week/domain ceiling), so quota pressure is not a real constraint. See `project_acme_provider` memory.

**Relevant:**
- Fixed by: `#389` → PR `#390` (cert provisioning in onboarding), `#392` → PR `#393` (VM-scope regression that broke the post-#389 flow), `#394` → PR `#396` (e2e cold-start test hardening)
- This was the motivating incident for [`.claude/rules/ux-changes.md`](../.claude/rules/ux-changes.md) itself — the decision was made unilaterally the first time; this rule exists so it cannot be made unilaterally again.
