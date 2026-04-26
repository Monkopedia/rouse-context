# Agent preamble

Read this once when launched. Apply to every agent task in this repo.

## Tooling

- Use `Read`, `Grep`, `Glob`, `Edit`, `Write` over their `bash` equivalents (`cat`, `grep`, `find`, `sed`).
- Spawn sub-agents with model `opus` for any code work.
- Run agents in the background unless the user asks otherwise.

## What to NOT touch

- Anything under `.claude/` (rules, agent-preamble, project memory): triggers approval prompts and blocks autonomous work. If you genuinely need a rule change, surface it to the user first.
- Signing keystores at `.signing/`: regenerating forces a forced uninstall on every device + burns ACME quota. Critical.
- Build outputs (`build/`, `.gradle/`, etc).

## Workflow

For any non-trivial change: design → approve → test → implement → verify. See `.claude/rules/workflow.md`.

UX-affecting changes (onboarding, permission timing, error surfaces, credential timing, etc) need explicit user approval AND an entry in `docs/ux-decisions.md`. See `.claude/rules/ux-changes.md` for what counts.

## Build & verify

`JAVA_HOME=/usr/lib/jvm/java-21-openjdk` for every Gradle command. See `docs/agent-quickstart.md` for module-specific recipes.

Always before committing:
- `./gradlew ktlintFormat` (formats Kotlin)
- `./gradlew :module:testDebugUnitTest ktlintCheck detekt` for the modules you touched
- For relay (Rust): `cargo fmt && cargo test && cargo clippy --all-targets --all-features -- -D warnings`

Match commit-message style — `git log --oneline -10` for recent examples.

## Branches and PRs

Branch name: `fix-NNN-short-description` for issue fixes.

PR lifecycle (must close out fully — no strays):
1. Open PR with `gh pr create`. Body links the issue (`Closes #NNN`).
2. Wait for CI green.
3. `gh pr merge NNN --squash --delete-branch`.
4. If local-branch deletion fails because another worktree has it checked out:
   ```
   cd /home/jmonk/git/rouse-context && git checkout --detach origin/main && git branch -d <branch>
   ```
5. Comment on the issue with the PR number; remove the `in-progress` label (the PR's `Closes` line auto-closes the issue).

Don't leave open PRs or stale local branches behind.

## When something goes wrong

- If you can't make progress: file a NEW GitHub issue describing the blocker (don't bury it as a comment on the parent issue), then stop. Don't speculate-patch.
- If you find a related bug while working: file as separate issue, link it, don't expand scope mid-PR unless the original task is bounded.

## Relay-specific

NEVER build the relay on the GCP VPS. CI deploys via GitHub Actions. To verify a deploy:
```
gcloud compute ssh relay --zone=us-central1-a --command='systemctl status rouse-relay | head -5'
```

## Devices

- `adolin` Pixel 6 Pro (`ANDROID_SERIAL=1B151FDEE008EY`) — agent-controlled test device. Drive via `ssh adolin "ANDROID_SERIAL=... /opt/android-sdk/platform-tools/adb …"`.
- The user's personal phone is NOT for autonomous testing. Always confirm before targeting any other device.
