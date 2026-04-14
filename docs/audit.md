# Documentation audit — 2026-04-14

Scope: every `.md` under `docs/`, plus top-level `README.md` and `BLOCKERS.md`.
Also audited HTML files under `docs/` since the issue called them out.
Out of scope: `.claude/` rules, KDoc in source, inline code comments.

Totals: 24 markdown files inventoried (23 under `docs/` or repo root) + 3 HTML
indexes + icon/screenshot PNGs.
- Current: 8
- Partially stale / needs update: 8
- Rewrite (either for docs/user/ or fresh engineering doc): 2
- Delete / archive: 4
- Not docs (HTML/images in `docs/`): 2 directories (plus one HTML sitting
  directly under `docs/notifications/`)

Module-level `README.md` / `BLOCKERS.md` files: none exist inside modules.
Top-level `BLOCKERS.md` is the only one.

---

## Per-doc findings

### README.md (repo root)
- **Length**: 93 lines
- **Audience**: contributor / user (top-level landing)
- **Accuracy**: current. Module table lists `:core:bridge`, outreach, usage,
  notifications — all present in the tree. Integration list matches what's
  shipped. URL form `brave-falcon.abc123.rousecontext.com/health/mcp` is the
  legacy format, not the per-integration form shipped in `valid_secrets`.
  Minor stale detail.
- **User-facing content?**: Yes, partially. It's the main marketing/what-is-it
  surface.
- **Recommendation**: update. Fix the URL example to match the shipped
  per-integration-hostname scheme (`brave-health.cool-penguin...`). Otherwise
  keep as-is. Consider splitting "marketing intro" into `docs/user/index.md`
  once the user docs tree exists.

### BLOCKERS.md (repo root)
- **Length**: 190 lines
- **Audience**: historical
- **Accuracy**: stale. References T-12 scaffolding blockers (no `:api`
  module, no ktlint plugin), an April 2026 FCM-wake flow that "couldn't
  connect because ACME is stubbed" — all long since resolved (relay uses
  a real ACME client, `:api` module exists, ktlint/detekt are configured).
- **User-facing content?**: No.
- **Recommendation**: delete. This was a handoff note between agents during
  early implementation; everything described here is fixed or superseded
  by GitHub Issues now.

### docs/workflow.md
- **Length**: 704 lines
- **Audience**: historical
- **Accuracy**: stale. Declares "the relay does not exist yet" and lays out
  phases 0–6 as a DAG. All phases are shipped. Talks about `mcp-core`,
  `mcp-health`, `tunnel` flat modules that were renamed long ago.
- **User-facing content?**: No.
- **Recommendation**: delete. This was the original implementation roadmap.
  GitHub Issues is now the tracking system (per project memory). If a
  historical record is wanted, preserve by tagging the last commit that
  contains it and then delete from `main`.

### docs/overnight-plan-2.md
- **Length**: 118 lines
- **Audience**: historical
- **Accuracy**: stale. A dated execution plan (2026-04-06) listing
  phase 1–7 bug fixes and integration work. Most items shipped; the rest
  live in GitHub Issues.
- **User-facing content?**: No.
- **Recommendation**: delete. Point-in-time plan, not a design doc.

### docs/device-test-report.md
- **Length**: 172 lines
- **Audience**: historical (point-in-time QA report)
- **Accuracy**: partially stale. Snapshots 2026-04-07 test on a Pixel 3 XL.
  Several "non-blocking issues" are now fixed (#5 tracked dashboard
  connection state; spurious disconnect/reconnect and state-machine
  idempotency fixes have landed).
- **User-facing content?**: No.
- **Recommendation**: delete or move to `docs/history/` if we want an
  archive of QA snapshots. It is not an evergreen doc.

### docs/e2e-status.md
- **Length**: 55 lines
- **Audience**: historical
- **Accuracy**: stale. Describes "current blocker: AI client → relay
  passthrough → device" as of 2026-04-06. Passthrough has shipped; this
  was resolved.
- **User-facing content?**: No.
- **Recommendation**: delete.

### docs/test-coverage-audit.md
- **Length**: 1012 lines
- **Audience**: engineering (historical audit)
- **Accuracy**: stale. Dated 2026-04-06, cross-references the same 12
  production bugs from `device-test-report.md`. Inventory of test classes
  has drifted (new tests since then). Gap lists are partially addressed.
- **User-facing content?**: No.
- **Recommendation**: delete. Redo as a fresh audit later if useful. A
  point-in-time audit with bug counts that rebase out of date quickly
  is not a doc to keep evergreen.

### docs/security.md
- **Length**: 237 lines
- **Audience**: user (despite being described as "engineering-focused" in
  the issue — the prose reads as a user-facing trust explanation: "Your
  phone's data never touches our servers", trust model, what-you-trust
  lists). Some sections (certificate architecture, renewal windows) are
  implementation detail.
- **Accuracy**: mostly current. Describes per-integration hostnames,
  ACME DNS-01, mTLS, OAuth 2.1 with PKCE — all shipped. References
  `docs/security-audit-2026-04-07.md` and `docs/security-decisions.md`.
- **User-facing content?**: Yes, strongly.
- **Recommendation**: rewrite for `docs/user/security.md` per #119.
  Keep the implementation-detail parts (certificate architecture,
  renewal windows, rate limit tables) in a separate engineering doc
  (`docs/engineering/security.md` or keep at `docs/security.md`). Trust
  model + what-you-trust should go user-facing.

### docs/security-audit-2026-04-07.md
- **Length**: 39 lines
- **Audience**: engineering / historical
- **Accuracy**: partially stale. Lists 23 findings. Many criticals
  (redirect URI validation, PKCE format, encrypted storage) were
  scheduled for "Week 1" and should be verified as addressed.
- **User-facing content?**: No.
- **Recommendation**: keep, but update. Annotate each finding with its
  resolution status (fixed / accepted per `security-decisions.md` /
  still open). Without status, it misleads readers into thinking all 23
  are still unmitigated.

### docs/security-decisions.md
- **Length**: 40 lines
- **Audience**: engineering
- **Accuracy**: current. Companion to `security-audit-2026-04-07.md` —
  records which findings were accepted as risk. Cross-references are
  valid.
- **User-facing content?**: No.
- **Recommendation**: keep as-is. Extend as new decisions are made.

### docs/self-hosting.md
- **Length**: 234 lines
- **Audience**: contributor (users forking and running their own deployment)
- **Accuracy**: current. Describes Cloudflare DNS, Firebase setup,
  keystore generation, `-Pdomain=` parameterization, relay systemd
  unit. Checked: `relay_hostname` + `base_domain` fields exist in
  `relay/src/config.rs`; keystore guidance aligns with project rules.
- **User-facing content?**: Partial — "sophisticated user/contributor"
  not "end user".
- **Recommendation**: keep, with a minor pass. Candidate to move into
  `docs/user/self-hosting.md` or `docs/contributor/self-hosting.md`
  when that tree exists.

### docs/design/overall.md
- **Length**: 737 lines
- **Audience**: engineering
- **Accuracy**: mostly current but long. Early sections (system
  architecture, identity model, Firestore schema, end-to-end flows) match
  shipped behavior. Later parts are a massive test-case list (items
  165–274) that overlaps `test-coverage-audit.md` and the actual test
  files — that part has drifted.
- **User-facing content?**: No — design detail.
- **Recommendation**: update. Keep the architecture/flow prose. Cut the
  embedded test-case enumeration (lines ~590–730) — it's not authoritative
  since the real tests diverge. Add a pointer to test classes instead.

### docs/design/android-app.md
- **Length**: 377 lines
- **Audience**: engineering
- **Accuracy**: partially stale. Module map is missing `:core:bridge`,
  `:outreach`, `:usage` — all shipped modules. `McpIntegration` interface
  sketch matches what exists in `:api`.
- **User-facing content?**: No.
- **Recommendation**: update. Reconcile module list with the actual
  project structure. Low effort.

### docs/design/relay.md
- **Length**: 336 lines
- **Audience**: engineering
- **Accuracy**: mostly current. Mux frame format, SNI routing, ACME
  bootstrap match the code.
- **User-facing content?**: No.
- **Recommendation**: keep, minor update pass. Verify subdomain-lookup
  caching behavior still matches implementation (skim only; flagged as
  "may have drifted; needs deeper review").

### docs/design/relay-api.md
- **Length**: 769 lines
- **Audience**: engineering (API reference)
- **Accuracy**: mostly current. Endpoints `/register`, `/renew`,
  `/wake/:subdomain`, `/rotate-secret`, `/ws`, `/status` all exist
  under `relay/src/api/`. No `/request_subdomain` mention though —
  that endpoint does exist in code. Likely minor drift.
- **User-facing content?**: No (API doc for integrators / contributors).
- **Recommendation**: update. Add `/request-subdomain`, cross-check
  field names against current request/response structs, confirm error
  code table is accurate. May have drifted; needs deeper review.

### docs/design/tunnel-client.md
- **Length**: 264 lines
- **Audience**: engineering
- **Accuracy**: mostly current. KMP structure, mux client
  responsibilities, FCM dispatch (wake vs renew), state machine all
  match shipped code. Claim "No reconnect during active streams" may
  conflict with auto-reconnect-with-backoff behavior added later —
  flagged as possible drift.
- **User-facing content?**: No.
- **Recommendation**: update. Minor reconciliation on reconnect behavior.

### docs/design/ui.md
- **Length**: 633 lines
- **Audience**: engineering
- **Accuracy**: partially stale. Screen enumeration (Welcome, Dashboard,
  etc.) and state model are mostly right. Some screens (e.g. Onboarding
  as banner vs full screen) evolved per issues like #5 and the overnight
  plan's non-blocking-onboarding goal. May have drifted; needs a deeper
  review against current AppNavigation + Screen composables.
- **User-facing content?**: No.
- **Recommendation**: update. Could be worth a sweep that cross-checks
  against actual nav graph and Screen files (per the "verify rendered
  path" memory rule).

### docs/design/per-integration-hostnames.md
- **Length**: 512 lines
- **Audience**: engineering (proposal)
- **Accuracy**: partially stale. Status is "proposed". Feature shipped
  but with a different data model: proposal said
  `integration_secrets: HashMap<String, String>`; code has
  `valid_secrets: Vec<String>` + deprecated `secret_prefix`. Design
  intent matches; schema does not.
- **User-facing content?**: No.
- **Recommendation**: update — change status to "implemented" and
  reconcile the schema section with the shipped `Vec<String>` +
  deprecated `secret_prefix` model. Or delete if we don't want to
  preserve proposal docs post-ship.

### docs/design/secret-prefix-bot-rejection.md
- **Length**: 178 lines
- **Audience**: engineering (proposal, predecessor to
  per-integration-hostnames)
- **Accuracy**: partially stale. Design shipped. Superseded by
  `per-integration-hostnames.md` which generalized it.
- **User-facing content?**: No.
- **Recommendation**: delete. Superseded.

### docs/design/notifications-integration.md
- **Length**: 70 lines
- **Audience**: engineering
- **Accuracy**: current (short design doc for the notifications module).
  Tools listed (`list_active_notifications`, `search_notification_history`,
  etc.) match what the integration exposes.
- **User-facing content?**: No.
- **Recommendation**: keep.

### docs/design/outreach-integration.md
- **Length**: 72 lines
- **Audience**: engineering
- **Accuracy**: current. Matches the `:outreach` module (launch_app,
  open_link, copy_to_clipboard, DND tools).
- **User-facing content?**: No.
- **Recommendation**: keep.

### docs/design/usage-integration.md
- **Length**: 51 lines
- **Audience**: engineering
- **Accuracy**: current. Matches `:usage` module tools.
- **User-facing content?**: No.
- **Recommendation**: keep.

### docs/design/health-connect-expansion.md
- **Length**: 59 lines
- **Audience**: engineering (proposal/expansion)
- **Accuracy**: partially current. `RecordTypeRegistry` exists in
  `:health`. Verify shipped tool names against `HealthConnectMcpServer`
  (may still expose the old `get_steps` alongside generic query, or may
  have fully migrated).
- **User-facing content?**: No.
- **Recommendation**: update. Quick pass to mark shipped vs still-planned
  record types.

---

## Non-markdown under docs/

### docs/notifications/index.html (+ icon_*.png)
- **What it is**: A standalone HTML notification catalog / design gallery
  (renders all notification variants for review), using local icon
  PNGs.
- **Accuracy**: Unknown in terms of whether the icons still match
  shipped notification channels. Not referenced from any
  markdown/code under the repo.
- **Recommendation**: move out of `docs/` into `design-assets/` or
  `tools/notification-preview/`. `docs/` should not contain loose
  HTML design tools — they won't make sense when the docs tree is
  published.

### docs/auth-pages/*.html
- **What it is**: Rendered gallery of OAuth auth-page states
  (approved / denied / expired / waiting / index). Built by
  `AuthPageGalleryTest` per `test-coverage-audit.md`.
- **Recommendation**: keep, or move to `app/src/test/resources/`
  since it's test-generated output, not a doc.

### docs/screenshots/index.html and docs/screenshot_*.png
- **What it is**: Screenshot gallery index + the four referenced
  screenshots from `device-test-report.md`.
- **Recommendation**: if we delete `device-test-report.md`, remove
  these too. Otherwise leave.

---

## Summary punch list

- [ ] **Delete**: `BLOCKERS.md`, `docs/workflow.md`, `docs/overnight-plan-2.md`,
      `docs/e2e-status.md`, `docs/test-coverage-audit.md`,
      `docs/design/secret-prefix-bot-rejection.md`. Optionally
      `docs/device-test-report.md` (plus `docs/screenshot_*.png` and
      `docs/screenshots/index.html` if report is deleted).
- [ ] **Rewrite for docs/user/**: `docs/security.md` → split into
      `docs/user/security.md` (trust model, what-you-trust, what-ai-can't-do)
      and keep the implementation-detail parts under
      `docs/engineering/security.md` (or keep current path).
- [ ] **Move out of docs/ tree**: `docs/notifications/index.html` + its
      `icon_*.png` files (they're a design asset, not docs).
      Optionally move `docs/auth-pages/*.html` to test resources.
- [ ] **Update**:
  - [ ] `README.md` — fix the URL example to the shipped per-integration
        hostname format.
  - [ ] `docs/security-audit-2026-04-07.md` — annotate each of the 23
        findings with current status.
  - [ ] `docs/design/overall.md` — drop the embedded test-case list
        (items 165–274) in favor of a pointer to test classes; keep
        architecture prose.
  - [ ] `docs/design/android-app.md` — reconcile module map with
        `:core:bridge`, `:outreach`, `:usage`.
  - [ ] `docs/design/relay-api.md` — add `/request-subdomain`, verify
        field names and error codes match code. Needs deeper review.
  - [ ] `docs/design/tunnel-client.md` — reconcile reconnect behavior
        with auto-reconnect implementation.
  - [ ] `docs/design/ui.md` — deeper review against current nav graph
        and Screen composables; mark any dead-code screens.
  - [ ] `docs/design/per-integration-hostnames.md` — status → implemented,
        schema → `Vec<String> valid_secrets` + deprecated
        `secret_prefix`. Or delete post-ship.
  - [ ] `docs/design/health-connect-expansion.md` — mark shipped record
        types.
- [ ] **Keep as-is**:
  - [ ] `docs/self-hosting.md` (candidate for `docs/contributor/` move).
  - [ ] `docs/security-decisions.md`.
  - [ ] `docs/design/relay.md` (minor review recommended).
  - [ ] `docs/design/notifications-integration.md`.
  - [ ] `docs/design/outreach-integration.md`.
  - [ ] `docs/design/usage-integration.md`.

---

## Gaps (user-facing content that should exist but doesn't)

- No `docs/user/` tree at all yet (gated on #119).
- No user-facing getting-started / install guide (today lives implicitly
  in `README.md` status line).
- No user-facing "how to enable an integration" walkthrough.
- No user-facing FAQ on data retention, audit review, revoking access.
- No user-facing explanation of subdomain rotation and its cooldown.

These would populate the `docs/user/` tree alongside the security rewrite.
