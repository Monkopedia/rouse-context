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
