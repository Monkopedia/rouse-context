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

## 2026-06-16 — FOSS delivery picker nudges the user to open a freshly-installed distributor (#480)

**Decision:** Option (a) detect-and-nudge. After the user picks a UnifiedPush
distributor in the Background-delivery picker, the picker waits briefly for the
push endpoint to arrive. If it doesn't (the freshly-installed "stopped state"
case), the picker surfaces a non-blocking, dismissible nudge with an action
button that **launches the chosen distributor** (its launch intent), which
clears Android's stopped state so the distributor processes our UnifiedPush
`REGISTER` and mints an endpoint. The endpoint then flows back, the device
registers, the nudge clears automatically, and the flow advances. The action
button copy is **"Open {distributor} to enable"** — the chosen distributor's
display name is interpolated (e.g. "Open ntfy to enable"); ntfy is the common
case but the picker can land on other distributors, so it is never hardcoded.
Advancing past the picker is now gated on the endpoint arriving (or the user
skipping) rather than firing immediately on tap.

**Approved by:** Jason, in-session 2026-06-16.

**Context:** A freshly-INSTALLED UnifiedPush distributor sits in Android's
"stopped" state and ignores broadcasts — including our `REGISTER` — until it's
launched once. So a user could pick a distributor in onboarding and have no
endpoint (`NEW_ENDPOINT`) ever arrive: the device stayed silently degraded with
no signal that opening the distributor once would fix it. Only the very first
setup is affected (once the distributor has been opened ever, it is no longer
stopped).

**Alternatives considered:**
- **(b) Proactively launch the distributor** for the user right after selection —
  more magical but yanks the user into another app unprompted, and may be blocked
  from the background. Rejected in favor of a visible, user-driven action.
- **(c) Document-only** — weakest; leaves the silent degrade in place with only a
  help-doc mention. Rejected.

**Trade-off accepted:** The picker no longer advances the instant a distributor
is tapped; it holds for the short endpoint-arrival window (then advances, or
shows the nudge / lets the user skip). A healthy already-launched distributor
adds a few seconds before auto-advance. The nudge is one more surface a
first-setup FOSS user may see, but it is the only signal that an otherwise
silent registration stall is one tap away from fixed.

**Relevant:**
- #480 (this change); follows the #463 picker and #460 FOSS-distribution epic.
- Builds on #486 (deferred-registration activation in
  `UnifiedPushBackgroundDelivery`).

---

## 2026-06-16 — Notification-listener access deep-links to our own toggle on API 30+ (#487)

**Decision:** In the notification-capture setup step, "Grant access" now deep-links
straight to our listener's own enable toggle on API 30+ via
`ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` +
`EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME` (our `NotificationCaptureService`
`ComponentName`). On API < 30 it falls back to the existing
`ACTION_NOTIFICATION_LISTENER_SETTINGS` (the full list). minSdk is 24, so the
fallback is required.

**Approved by:** Jason, in-session 2026-06-16 — greenlit the audit-driven
permission-UX improvements.

**Context:** The 2026-06-16 permission-UX audit flagged that the old action drops
the user on the full list of every notification-listener app, where they have to
hunt for ours. The detail action lands them on our toggle directly.

**Alternatives considered:** Keep the list action everywhere (rejected — extra
hunting for no benefit on capable devices).

**Trade-off accepted:** None of substance — same onboarding step, same intent
purpose; only the deep-link target tightens (list → our toggle). Treated as a
targeting improvement, not a flow change.

**Relevant:** #487.

---

## 2026-06-16 — FOSS Home battery-optimization warning; "Fix this" becomes the one-tap OS dialog (supersedes 2026-06-02 #453)

**Decision:** Two parts (#483).
1. **New FOSS-only Home banner.** On the FOSS build, Home shows a persistent
   warning banner ("Battery optimization is on") whenever the app is NOT on the
   battery-optimization allowlist. On FOSS that exemption is required for
   background wake to fire at all — a UnifiedPush distributor can't grant our app
   the temporary power-allowlist that Play Services hands the FCM build, so
   without the standing exemption the background start of the tunnel foreground
   service is blocked and the wake is dropped. The banner mirrors how ntfy
   surfaces its own battery warning, and sits alongside (and can co-exist with)
   the existing "On-demand wake is off" delivery banner — a distinct condition
   (that one = no distributor chosen; this one = distributor set up but the app
   can't start the FGS from background without the exemption). Never shown on the
   google/FCM build, which gets the temporary allowlist automatically.
2. **"Fix this" now opens the one-tap OS dialog.** Both the new Home banner's
   action AND the existing #453 Connection-settings "Fix this" now launch
   `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (the one-tap allow/deny dialog,
   with a `package:` URI) instead of `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`
   (the settings list the user hunts through). This declares the
   `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission. **This supersedes the
   2026-06-02 #453 decision** ("Fix this opens battery-optimization settings").

**Approved by:** Jason, in-session 2026-06-16 — picked the detect-and-warn option
and, in a follow-up amendment, the one-tap dialog mechanism for both surfaces.

**Context:** Found during the 2026-06-16 FCM-vs-ntfy wake benchmark (#483): the
FOSS ntfy wake path didn't fire at all until the app was battery-exempt. The
#453 "Fix this" deliberately used the settings-list action solely to dodge the
Google Play policy restriction on `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. We
don't ship to Play — the Google build is GitHub-distributed and the FOSS build
goes to F-Droid, which accepts the permission (ntfy itself uses it and ships on
F-Droid) — so that constraint no longer binds, and the better one-tap flow is
available.

**Alternatives considered:**
- **Request the exemption up front during FOSS delivery setup** (option (a) on
  #483) — most reliable, but adds an onboarding permission surface. Rejected in
  favor of degrade-and-warn, consistent with the delivery banner's "degrade,
  don't block" model.
- **Document-only** (option (c)) — weakest; leaves wake quietly unreliable.
- **Keep the settings-list action for "Fix this"** — no Play risk, but a multi-tap
  hunt-for-the-app flow when a one-tap dialog is available and policy no longer
  blocks it.

**Trade-off accepted:** The app now declares a Play-restricted permission, so it
could not ship to Google Play as-is — acceptable because we never do (Google
build is GitHub-distributed, FOSS goes to F-Droid). The Home banner is one more
warning surface FOSS users may see, but it is the only signal that an otherwise
silent background-wake failure is fixable.

**Relevant:**
- #483 (this change); part of epic #460 (the FOSS distribution).
- Supersedes the 2026-06-02 #453 "Fix this opens battery-optimization settings"
  decision below.
- Battery-opt status + intent shared via `BatteryOptimization`; foss-gated at
  runtime via `BackgroundDelivery.isSupported` (the same seam the delivery banner
  uses). Exemption re-read on ON_RESUME via `batteryExemptFlow`.

---

## 2026-06-16 — Add integration on an unregistered FOSS device redirects to the delivery picker (auto-resume)

**Decision:** Option A — auto-redirect + auto-resume. On a not-yet-registered
FOSS device (one that tapped "Skip for now" on Background delivery, so it sits on
a degraded Home with no push endpoint), tapping **Add integration** no longer
silently blocks in `IntegrationSetupViewModel.awaitRegistrationIfNeeded` (which
read like a hang). Instead:
- It **redirects to the existing Background delivery picker** (#463), topped with
  a contextual strip: **"Set up a delivery app to finish adding integrations."**
- It **remembers the integration the user was adding** (a single pending-integration
  id carried across the picker round-trip).
- **Auto-resume:** once a distributor is chosen, the user drops straight back into
  that integration's setup flow (not back to Home). The setup screen's existing
  "Registering" indicator covers the brief deferred-registration wait, so the
  hand-off is legitimate rather than a silent block.
- If the user instead taps "Skip for now" on the picker, the pending integration
  is dropped and they return to Add integration (nothing to resume into).
- **Gated on the delivery-not-set-up state** (`DeliveryActivation.NeedsSetup`), so
  it is a no-op on `google` (FCM, `NotApplicable`) and on already-registered FOSS
  devices (`Active`), which proceed straight into setup as before.

**Approved by:** Jason, in-session 2026-06-15 — reviewed rendered Roborazzi mocks of
four candidate shapes (silent-hang baseline, A auto-redirect, B explanatory dialog,
C inline banner) and picked **Option A + auto-resume**.

**Context:** The FOSS deferred-activation model (2026-06-13 entry) lets a user skip
Background delivery and reach a degraded Home. But the integration-setup flow waits
for registration to complete before provisioning, and on a skipped device that
registration never starts — so "Add integration" appeared to hang with no
explanation and no way forward.

**Alternatives considered:**
- **B — explanatory dialog** before routing onward. Adds the most net-new UI for
  the least payoff; the picker itself already explains what's needed.
- **C — inline banner on a dead-end screen.** Leaves the user parked on a screen
  they have to back out of, rather than moving them toward the fix.
- **Return to Home after registration** (instead of auto-resume). Rejected: it
  breaks the "finish what you started" flow the strip copy promises and forces the
  user to re-find and re-tap the integration.
- A is the most faithful to the existing flow (it *is* the real #463 picker),
  the lowest-risk build, and the smallest new surface.

**Trade-off accepted:** "Add integration" can now detour through a second screen
before setup, and the carried pending-integration id is process-scoped (not
persisted) — if the process dies mid-detour, the user simply re-taps the
integration. We accept that over a heavier persisted-resume mechanism.

**Relevant:** #474 (this change), #463 (the picker), #460 (the FOSS flavor).

---

## 2026-06-13 — FOSS flavor adds a "Background delivery" picker (deferred activation)

**Decision:** The fully-FOSS build flavor (`foss`, for F-Droid — see #460) replaces
FCM with [UnifiedPush](https://unifiedpush.org/), which needs a separate
*distributor* app on the device. This adds one new first-run step, **"Background
delivery,"** placed after Welcome. It is a single **picker screen** (not a
multi-step install→connect→continue wizard): it lists the UnifiedPush distributors
already installed as one-tap rows, plus an "Install another app" row that
deep-links to a store. Specifics:
- **ntfy is the cold-start fallback only.** If *no* distributor is installed, the
  list suggests ntfy + "install another app." If *any* supported distributor is
  present — even a non-ntfy one — the list shows exactly what's installed +
  "install another app," and never injects an "install ntfy" row.
- **Degrade, don't block (deferred activation).** "Skip for now" completes
  onboarding to Home without forcing a distributor install — but because a FOSS
  device has no push endpoint until a distributor is chosen and the relay
  requires a push target to register, a skipped device is **not yet
  registered/active** and shows a persistent "On-demand wake is off — set up a
  delivery app" banner. Choosing a delivery app reports its endpoint, registers
  the device, and activates it. *(The 2026-06-11 draft said it "serves
  foregrounded meanwhile"; that assumed endpoint-less registration, which the
  merged relay #472 doesn't allow — Jason chose this app-only deferred-activation
  model on 2026-06-13, over a relay change or making the picker mandatory.)*
- **Changeable later.** A persistent "Background delivery" row in Settings reopens
  the same picker (active distributor marked), so the choice isn't one-time-only.
- **Default backend for now is the public ntfy.sh instance** (whatever the chosen
  distributor points at). Self-hosting our own UnifiedPush server is a noted
  follow-up, not in scope for the first cut.
- The picker **re-scans installed distributors on `ON_RESUME`**, so leaving to
  install a distributor and returning refreshes the list.
- This entry is the `google`-flavor-invisible side of the build: the `google`
  flavor keeps FCM and shows none of this; its onboarding gate/flow is unchanged.

**Approved by:** Jason, in-session 2026-06-11 — reviewed rendered Roborazzi mocks
(picker cold-start / some-installed / non-ntfy-only / active-from-Settings /
Settings row / degraded banner), directed the picker-not-wizard redesign and the
condensed copy, confirmed ntfy-as-default and ntfy-only-when-empty. The
deferred-activation correction above was decided in-session 2026-06-13 (option A)
after the merged relay (#472) made a push target mandatory at register time.

**Context:** F-Droid won't accept a build that links Firebase, so the FOSS flavor
can't use FCM for on-demand wake. UnifiedPush is the FOSS-standard replacement, but
unlike FCM it requires the user to have a distributor app — an unavoidable new
onboarding surface that code review alone can't vet, hence this entry. Push *is* the
product for FOSS (the device is woken on demand), so the step can't be deferred to
"first integration" the way an optional permission could. The relay half (#472)
landed first and made `/register` require a non-empty push target (`fcm_token` or
`push_endpoint`); a FOSS device only obtains a `push_endpoint` after a distributor
is chosen, which is why registration is deferred to that moment.

**Alternatives considered:**
- **Multi-step wizard** (install → detect → connect → continue, gated Continue
  button) — the first mock. Rejected as overkill; a one-tap picker conveys the same
  thing with far fewer controls.
- **Hard-block onboarding until a distributor is connected** — guarantees wake
  works, but stranding a user who can't immediately install a second app from
  another store is hostile; degrade-with-banner matches how the app already
  surfaces cert/battery/notification gaps.
- **Relay change so FOSS can register without a push target** (subdomain/cert
  issued, wake disabled until an endpoint is reported) — would make the *original*
  2026-06-11 "serves foregrounded meanwhile" promise literally true, but needs a
  new relay PR on top of the already-merged relay half. Deferred as a follow-up;
  option A (app-only deferred activation) ships now without touching the relay.
- **Self-host our own UnifiedPush server as the default backend** — strongest
  privacy story, but real infra work; deferred as a follow-up. ntfy.sh's metadata
  exposure is ≈ parity with today's FCM, so this isn't a regression from `google`.

**Trade-off accepted:** FOSS users take on a one-time "install a delivery app" step
that `google`-flavor users never see — the genuine UX cost of dropping Firebase.
A FOSS device that taps "Skip for now" finishes onboarding but is **not registered
and not reachable** (not even foregrounded) until a delivery app is set up — the
deferred-activation cost of the merged relay requiring a push target at register
time. Foregrounded-only operation has little value for an on-demand MCP server, so
this is acceptable; the optional relay follow-up above could restore a
"serves-foregrounded-while-wake-is-off" mode later. And with the public ntfy.sh
default, wake-timing metadata transits a third party (no worse than FCM today, but
not yet eliminated).

**Relevant:**
- Epic #460; this is issue **#463** (UnifiedPush wake path). Relay half merged in
  #472 (per-device push target, `push_endpoint` at `/register` + WS refresh).
- The 2026-06-11 in-session mock review established the picker design; this entry
  supersedes its skip-path wording only (picker-not-wizard, ntfy-only-when-empty,
  Settings entry, ON_RESUME rescan, ntfy.sh default all still hold).

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
