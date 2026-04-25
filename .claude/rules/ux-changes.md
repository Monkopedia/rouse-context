# UX Changes Require Explicit Approval

Some changes affect the product's user-facing flow in ways that code review alone cannot catch. For those changes, explicit user approval is required BEFORE implementation starts, AND a new entry must be added to [`docs/ux-decisions.md`](../../docs/ux-decisions.md) as part of the same PR.

Filing an issue with options and a ranked recommendation is NOT approval. A GitHub issue is context; the user's in-session reply is the approval.

## Requires approval (in-session + decision-log entry)

- Onboarding step added, removed, or reordered
- Timing of cert / credential / subdomain provisioning (when it fires in the flow)
- First-run UX (the Welcome → NotificationPreferences → Home path, including anything on that path)
- Permission prompt timing (when system permission dialogs appear relative to user actions)
- Error surface changes — where an error lands (new destination, different screen), how the user recovers (new button, different navigation), whether an error is shown at all

## Does NOT require approval

- Visual tweaks — spacing, colors, typography, layout within a screen
- Bug fixes that restore previously-intended behavior (the prior behavior was the approved one)
- Copy wording at the same step, same flow position, same intent
- New screens that are pure additions behind existing entry points (e.g. new Settings sub-pages, new Help content)
- **Documentation updates that describe already-shipped behavior** — bringing design docs, READMEs, or audits in line with what the code already does. No code changes, no behavior changes. This is doc maintenance, not UX. (Don't conflate "rewriting design docs to match shipped code" with "changing the design.")

## Gray zone — ask

- Adding a new integration (new screens, but follows an established pattern)
- Moving features between Home and Settings
- Notification copy or routing changes

If you are unsure whether a change falls into "requires approval", ask. A 30-second check-in costs nothing; an agent-implemented UX shift costs a rollback.

## How to ask

1. Surface the decision early: "this bug has 2+ defensible fixes with different UX trade-offs — which direction?"
2. Present options with trade-offs (not just a recommendation).
3. Wait for explicit approval ("sure", "go ahead", "looks good", etc.). A pending question is not approval.
4. Only AFTER approval: write tests, implement, open PR. Include the new `docs/ux-decisions.md` entry in the same PR.

## Why this rule exists

On #389, fresh-install cert provisioning was broken. The fix had three defensible shapes (onboarding-time provision, background provision, first-integration-time provision). An agent was launched to implement the first without explicit approval, based on a ranked recommendation in the issue. The user later observed the UX shift — "Registering" appears before any integration is picked — and noted that filing an issue with options is not the same as getting approval. See the `#389` entry in [`docs/ux-decisions.md`](../../docs/ux-decisions.md).
