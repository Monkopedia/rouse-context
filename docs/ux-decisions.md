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

## 2026-09-09 — `Never` added to the security check interval

**Decision:** `SecurityCheckIntervalOption` gains a fourth value, `Never`,
alongside 6/12/24 hours. Selecting it stops the periodic `SecurityCheckWorker`
entirely — no CT log query to crt.sh or Certspotter, and no local self-cert
check either. Default is unchanged (12 hours); `Never` is an added option, not
a new default, and nothing becomes opt-in. The trust card's two rows then read
"Turned off" with an overall status of "Checks off" rather than showing the
last run's result.

**Approved by:** Jason, in session 2026-09-09 — witnessed and recorded by the
repo agent, not by the implementing agent, at
[`#741 (comment)`](https://github.com/Monkopedia/rouse-context/pull/741#issuecomment-5604042230),
which quotes him verbatim. He proposed this design himself: presented with a
plan for a separate CT-specific toggle, he pushed back with *"is it just a
switch or should it just be a different value in the interval? like never"* —
the shape this entry describes. He also set the sequencing: *"if we're adding
an off switch the order is, add an off switch, create a new release, then
respond to linsui after I approve the message"*.

The approval covers the **mechanism** (a `Never` value on the existing interval
control rather than a second toggle) and the **order of work**. It does not
cover the reply to `fdroiddata!42096`, which he explicitly reserved for his own
sign-off.

**Gating:** this entry is **voluntary**. Adding an option to an existing
Settings control is a pure addition behind an existing entry point, which
[`.claude/rules/ux-changes.md`](../.claude/rules/ux-changes.md) lists under
"does NOT require approval". The owner approval above is stronger than the rule
asks for. A future reader should not read this entry as a mandatory gate that
was nearly missed.

**Context:** An F-Droid reviewer on `fdroiddata!42096` found the app queries
public Certificate Transparency logs on a schedule with no way to turn it off —
`HttpCtLogFetcher.kt` (crt.sh) and `CertspotterCtLogFetcher.kt`, both in the
shipped dex, with the per-device hostname as the query value. `SettingsScreen`
offered only cadence choices. F-Droid maintainer linsui is deciding antifeature
labels on that MR.

**Alternatives considered:**
- **A CT-specific switch that leaves the local self-cert check running** — the
  first design, dropped. It exposes an implementation detail as UI: a user does
  not know the check has two internal sources, and "check every 12 hours, but
  not the certificate-transparency part" is harder to explain than one cadence
  with `Never` in it. It also answers the reviewer less cleanly.
- **Encoding `Never` as a sentinel hour count (0 or -1)** — rejected.
  `forHours()` snaps unrecognised values to `HOURS_12`, so a stored "never"
  would have been one stale read away from decoding back into a live 12-hour
  cadence and silently resuming the egress the user switched off. Persisted as
  a separate boolean instead; `NEVER.hours` is null so no integer can name it.

**Trade-off accepted:** A user on `Never` loses the local self-cert check too,
which costs security for no privacy gain on its own. Accepted because the
interval already governs the whole feature — `SelfCertVerifier` and
`CtLogMonitor` are each reachable only via `SecurityCheckSources` →
`SecurityCheckWorker`, with no other caller and no other scheduler — so `Never`
completes the existing control rather than carving a second one into it. It is
clearly labelled, and it is what "never" means.

Also accepted: an unacknowledged `alert` keeps showing while checks are off,
rather than being relabelled "Turned off". The alert gate in `McpSession` still
blocks integration requests on the stored value, and hiding a live block would
leave the user unable to see why requests are failing.

**Relevant:**
- `fdroiddata!42096` (the review), `docs/security.md` (user-facing description)
- Enforced in two places on purpose: `SecurityCheckScheduler.cancelPeriodic`
  removes the scheduled work, and `SecurityCheckWorker` re-checks the flag
  before touching either source. The second is not redundant —
  `TunnelForegroundService.triggerOpportunisticSecurityCheck` enqueues a
  *one-time* run in a different unique-work slot that the scheduler cannot
  cancel, and it fires whenever the last check is stale. Under `Never` the
  last-check time stops advancing, so scheduler-only cancellation would have
  made the CT queries *more* frequent — one per tunnel connect.

---

## 2026-09-08 — Crash reporting is opt-in, asked once on first run (#546)

**Decision:** On the FOSS distribution crash reporting is **opt-in, and only
ever opt-in** — it is never enabled without an explicit user action. Two
surfaces, and no more:

- A **modal bottom sheet over the dashboard, shown exactly once.** Title *"Send
  crash reports?"*, a `PrivacyWarningCard` reading *"Reports become issues on a
  public GitHub tracker. Anyone can read them."*, a one-paragraph summary of the
  payload, an expandable *"What gets sent, in full"* disclosure, and
  **[Not now] [Turn on]**. Footer: *"Off unless you turn it on. Change any time
  in Settings › Support."*
- A **"Send crash reports" switch in Settings › Support**, below "Report a bug".

**Shown once means once, by any exit** — Turn on, Not now, swipe-dismiss or
back all record the ask, and there is deliberately **no second prompt**.
Settings is the only other route in. Dismissing leaves reporting OFF.

**Approved by:** Jason, in-session, including the rendered mockup of both
surfaces (`Main.dc.html` / `SettingsRow.dc.html` artboards). A first-run consent
prompt is approval-gated by [`ux-changes.md`](../.claude/rules/ux-changes.md);
the Settings switch on its own would not have been.

**Context:** The FOSS build shipped ACRA on by default with no opt-out, while
the shipped F-Droid listing claims *"Private by design — data never leaves the
device except through a live, user-approved session"* and *"no analytics or
tracking in the app"*. An F-Droid reviewer found the same thing independently
against the built APK on `fdroiddata!42096` (note 3804211192) while the
packaging MR was open. Payload size was never the defect; a shipped store
listing making a false privacy claim is.

**Alternatives considered:**
- **Correct the listing text instead** (disclose default-on reporting). Rejected
  on #546: F-Droid users pick that build *for* the asserted property, and the
  assertion is made in the strongest available form ("never", "no analytics").
  Removing the property rather than honouring it is the worse answer.
- **Settings switch only, no first-run prompt.** Cheaper, and not
  approval-gated. Rejected because it makes the honest default —
  reporting off — also the silent one: nobody who would happily opt in ever
  learns the option exists, and crash visibility goes to roughly zero.
- **A blocking consent step inside onboarding.** Rejected: consent to an
  optional diagnostic is not worth a gate on first run. The sheet is modal but
  dismissible, and dismissal is a complete answer.
- **Ask again later** (re-prompt after N launches / after a crash). Rejected as
  nagging. One ask, then Settings.

**Trade-off accepted:** Crash reports will drop sharply — most users will take
"Not now" or swipe the sheet away, and there is no second chance to ask. That is
the cost of the listing being true, and the issue judged it the cheapest thing
on the table given how few users the build has so far.

**Deliberately unchanged:** the **google/Play distribution** keeps #233's
behaviour (Crashlytics collects in release builds, no consent UI). It ships no
consent surfaces, so gating it here would disable Crashlytics with no way for a
user to turn it back on — a separate decision for the owner, not a side effect
of this one. The distinction is one injected Koin flag,
`crashReportingRequiresOptIn`, pinned by a test in each distribution's test
source set.

**Relevant:**
- Issue: `#546`. Related: `#233` (Crashlytics gate), `#464` (ACRA), `#516` (ANR
  reporting — `needs-decision`, and it inherits this gate), `fdroiddata!42096`.
- Screens: `CrashReportConsentSheet.kt` (new component),
  `MainDashboardDestination.kt` (host), `SettingsScreen.kt` (Support switch).
- Screenshots: `app/screenshots/90_crash_consent_sheet_{light,dark}.png`,
  `91_settings_crash_reports_off_{light,dark}.png`,
  `92_settings_crash_reports_on_{light,dark}.png`.

---

## 2026-08-26 — Health Connect setup no longer auto-advances on the base grant (#537)

**Decision:** In `SetupMode.SETUP`, granting the base Health Connect
permissions **keeps the user on the Health Connect setup screen** instead of
navigating straight to the integration-setup screen. The primary button becomes
state-aware: it reads **"Grant All Health Access"** until at least one record
type is granted, and **"Continue"** afterwards — and Continue is what performs
the navigation that the grant result used to perform. `SetupMode.SETTINGS` is
untouched.

**Approved by:** Jason, in-session, after reviewing a rendered mockup of the
post-grant state:
- *"ok, we can do option 2"*
- *"we need to change the text to something like continue"*
- *"keep both as is, I think Continue/Cancel is most correct for the app
  currently, although we could re-evaluate cancel across the board at a later
  date"*

**Context:** The "Grant historical access" button (the
`READ_HEALTH_DATA_HISTORY` permission — without it, reads are capped at the last
30 days) is disabled until the base permission lands. But the base grant
immediately navigated away, so the button was never in a pressable state: it was
unreachable for the entire onboarding flow. Option 2 from #537.

**Cancel is deliberately unchanged** — same label, same behaviour (navigate to
the integration-manage screen). Its semantics across the app are being
re-evaluated separately, so this PR does not touch them.

**Alternatives considered:**
- **Option 1 — hide the historical button during onboarding** (offer it only
  from Settings). Simpler diff, but it drops the one moment where the user is
  already thinking about health permissions, and leaves an
  every-read-is-30-days-capped integration as the default outcome.
- **Auto-request the historical permission right after the base grant** (two
  system dialogs back to back). Rejected implicitly by choosing option 2:
  chaining permission prompts without a user action between them is exactly the
  pattern the permission-timing rule exists to prevent.

**Trade-off accepted:** Onboarding gains one deliberate tap — the user now
presses Continue instead of being carried forward by the grant result. Accepted
because that tap is the only thing that makes historical access reachable, and
the post-grant screen is where the user is already looking.

**Relevant:**
- Issue: `#537`
- Screens: `HealthConnectSetupScreen.kt` (button state),
  `HealthConnectSetupDestination.kt` (navigation)
- Screenshot: `app/screenshots/25a_health_connect_setup_granted_{light,dark}.png`
  — the post-grant state, which no user could previously see.

---

## 2026-06-28 — FOSS Home wake banner distinguishes "finishing setup" from "needs setup" (#530)

**Decision:** The foss Home on-demand-wake banner now has **two** distinct
states keyed off a new `DeliveryActivation.PendingSetup` value, instead of one
catch-all degraded banner:

- **PendingSetup** (a distributor IS saved, but its endpoint hasn't arrived yet —
  the legitimate deferred-activation window between landing on Home and the
  distributor delivering `NEW_ENDPOINT`): a **quiet, neutral** "finishing setup"
  indicator. Title **"Finishing delivery setup…"**, subtitle **"Connecting to
  your delivery app. On-demand wake will turn on once it's ready."** Neutral
  `secondaryContainer` surface + `Sync` icon (mirroring the cert "Renewing"
  in-progress banner), **no CTA**. Reads as in-progress, not failed.
- **NeedsSetup** (no distributor saved at all, or a genuine post-attempt
  registration failure): the existing **degraded** "On-demand wake is off / Set
  up a delivery app" banner (warning styling + "Set up" CTA) — unchanged copy.

Additionally, Home's `ON_RESUME` now calls `BackgroundDelivery.reRegisterIfPending()`:
when activation is `PendingSetup` and a distributor is saved, it re-requests the
endpoint (`UnifiedPush.registerApp`, idempotent). This self-heals the genuinely
stuck case (a stopped-state distributor that silently dropped our original
register) without the user having to redo delivery setup.

The google flavor reports `NotApplicable` and shows **neither** banner — verified
in `MainDashboardViewModelTest`.

**Approved by:** Jason, in-session (refined fix direction in #530's comment:
suppress the alarming banner during the pending window, show a neutral
"Finishing delivery setup…" instead, and keep the real degraded banner only when
nothing is configured / a real failure occurred).

**Context:** On a fresh FOSS onboard, after picking a UnifiedPush distributor,
Home flashed the alarming "On-demand wake is off / Set up a delivery app"
degraded banner during the deferred-activation window — falsely implying setup
failed when registration was simply pending. It usually self-cleared when the
endpoint landed, but could stay stuck if the endpoint never arrived. The fix
handles both: the neutral indicator removes the false alarm, and the
Home-resume re-register self-heals the stuck case.

**Alternatives considered:**
- *Show nothing at all during PendingSetup.* Rejected: a brief unexplained gap
  before wake activates is more confusing than a quiet "finishing…" cue, and
  hides the genuinely-stuck case entirely.
- *Keep the single degraded banner but add a timeout before showing it.*
  Rejected: a timer racing the async endpoint is fragile and still mislabels the
  pending window as a failure.
- *Surface the #480 "open your delivery app" nudge on Home too.* Deferred: the
  silent `registerApp` re-drive is less intrusive; the explicit nudge stays on
  the picker where the user is already acting.

**Trade-off accepted:** A third Home banner state to maintain, and a new
`DeliveryActivation` value threaded through the seam, the VM mapping, and every
`BackgroundDelivery` implementer. Worth it to stop the false-alarm and to
self-heal the stuck endpoint without a manual redo.

**Relevant:** #530 (this fix), #486 + #489 (prior partial fix — re-keyed
activation off the persisted subdomain), #480 (distributor stopped-state nudge).

---

## 2026-06-23 — FOSS-only "Ignore daily time limit" toggle (specialUse FGS)

**Decision:** A new **"Ignore daily time limit"** `SwitchRow` is added to the
Connection settings cluster, **foss flavor only**. Title **"Ignore daily time
limit"**, subtitle **"Avoids Android's 6-hour daily limit on background
connections. Idle timeouts still apply."**, default **OFF**. When ON, the tunnel
foreground service enters the foreground as `specialUse` instead of `dataSync`,
which has no Android 15 6h/24h cumulative cap.

Critical behavior: this toggle **only changes the foreground-service type**. It
does **not** disable or alter idle timeouts — the adaptive Idle-timeout /
Quick-disconnect teardown (2026-06-23 entry below) still governs when the tunnel
drops. The `specialUse` type merely removes the 6h ceiling so a legitimately
long, active day is not guillotined mid-session.

The row is gated on an **injected capability flag** (`canIgnoreDailyLimit`, bound
`true` in the foss `DistributionModule`, `false` on google) — not a
`BuildConfig.FLAVOR` string check. The google build never declares the
`specialUse` type or the `FOREGROUND_SERVICE_SPECIAL_USE` permission, and never
renders the row.

**Approved by:** Jason, in-session (copy + foss-only scope + the corrected
behavior that it ONLY changes the FGS type, not timeouts).

**Context:** Android 15 caps `dataSync` foreground services at 6h cumulative per
24h. For a foss user who keeps the tunnel connected for long interactive MCP
sessions, a genuinely active day can hit that ceiling and get the service killed
mid-use. `specialUse` removes the cap. This is foss-only because declaring
`specialUse` triggers Google Play review, and Play steers persistent-connection
use cases to FCM (which the google build already uses for wakeups) — so the Play
build must stay `dataSync`-only and never declare `specialUse`.

**Alternatives considered:**
- **Ship for both flavors** — rejected: declaring `specialUse` on the Play build
  invites Play-review friction with no upside, since google already wakes via
  FCM.
- **Make the toggle also relax idle timeouts** — rejected as a footgun: the only
  thing the user needs to escape is the 6h *ceiling*; idle teardown still
  protects the budget and the battery. Owner explicitly corrected an earlier
  framing here.
- **`BuildConfig.FLAVOR` checks in the UI** — rejected; the codebase already
  gates flavor-specific UI on injected capabilities (e.g. `BackgroundDelivery.
  isSupported`), so this follows that pattern.

**Trade-off accepted:** One more switch in the foss Connection cluster, and a
foss user who turns it on relies on the idle timeouts (not a hard ceiling) to
bound foreground time — acceptable since those timeouts still fire.

**Relevant:**
- `FgsTypeSelector` seam in `:work`, bound per-distribution
  (`FossFgsTypeSelector` / `GoogleFgsTypeSelector`). foss reads a synchronously-
  cached `IgnoreDailyTimeLimitState` mirror of `AppStatePreferences.
  ignoreDailyTimeLimit`.
- foss manifest overlay sets `foregroundServiceType="dataSync|specialUse"` +
  `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`; google merged manifest stays `dataSync`.
- The existing `onTimeout` / `ForegroundServiceStartNotAllowedException`
  dataSync-budget handling is unchanged; it simply does not fire while running
  as `specialUse`.

---

## 2026-06-23 — Adaptive post-session teardown + new "Quick disconnect" setting (both flavors)

**Decision:** The post-session idle timeout is now **adaptive**. A wake cycle is
"substantive" if its MCP stream issued at least one `tools/call`; otherwise it is
discovery-only (`initialize` / `ping` / `tools/list` / `resources/read` /
`prompts/get`) or spurious (no stream ever opened). When the idle timer arms on
return to CONNECTED:
- substantive last cycle → the existing **"Idle timeout"** (minutes; unchanged).
- discovery-only or spurious wake → the new, shorter **"Quick disconnect"**
  timeout (seconds).

A new **"Quick disconnect"** `SettingsDropdown` sits directly below "Idle
timeout" in the Connection settings cluster. Options **"15s" / "30s" / "1 min"**,
default **30s**, with the caption **"After a wake with no active session"**. The
existing "Disable timeout" switch is unchanged: when timeout is disabled, neither
timer arms (substantiveness is irrelevant).

**Approved by:** Jason, in-session (copy + behavior signed off).

**Context:** Android 15 caps `dataSync` foreground services at 6h cumulative per
24h. A lightweight wake — e.g. an MCP client that only does `resources/list` /
`tools/list` and then idles — previously held the tunnel CONNECTED for the full
idle timeout (up to ~10 min) for ~1s of real work, burning the 6h budget. The
fix shortens only the *post-discovery* timeout so spurious/discovery-only wakes
release the foreground service quickly while genuine tool-using sessions keep the
familiar idle grace period.

**Alternatives considered:**
- **Globally shorten the idle timeout** — would also cut short real tool-using
  sessions where the user expects a grace window. Rejected.
- **Make it foss-only / tie it to the `specialUse` toggle** — orthogonal; the
  budget pressure applies to both flavors, so this ships for both. The
  foss-only "Ignore daily time limit" toggle is a separate change.

**Trade-off accepted:** A second timeout knob in Settings (more surface), and a
discovery-only client that idles will now be disconnected in seconds rather than
minutes — if it comes back it triggers a fresh wake. Idle timeouts are unchanged
in spirit; only the no-active-session timeout is shortened.

**Relevant:**
- Substantiveness signal rides the existing `AuditListener.onToolCall`
  (`SessionActivityAuditListener` → `SessionActivityTracker`), read by
  `IdleTimeoutManager` when arming.

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
