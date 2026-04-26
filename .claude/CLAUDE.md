# Rouse Context

Android app + relay server that turns a phone into a secure, on-demand MCP server. For architecture, see `docs/design/overall.md` and `docs/design/android-app.md`.

## Build & Test

`JAVA_HOME=/usr/lib/jvm/java-21-openjdk` for every Gradle command.

```bash
# Build a module
./gradlew :core:mcp:assembleDebug

# Unit tests
./gradlew :core:mcp:testDebugUnitTest

# Lint & static analysis (whole tree)
./gradlew ktlintCheck detekt

# Auto-fix formatting
./gradlew ktlintFormat

# Relay (Rust, separate)
cd relay && cargo test && cargo clippy --all-targets --all-features -- -D warnings
```

For module-specific recipes and common commands, see `docs/agent-quickstart.md`.

## Workflow

Non-trivial change: design → approve → test → implement → verify. See `.claude/rules/workflow.md`.

UX-affecting changes (onboarding, permission/credential timing, error surfaces) require explicit user approval and a `docs/ux-decisions.md` entry. See `.claude/rules/ux-changes.md`.

When launching a sub-agent, point it at `.claude/agent-preamble.md` for the standard rules.

## Code Style

- ktlint with `android_studio` style (see `.editorconfig`)
- detekt with coroutine rules enabled (see `config/detekt/detekt.yml`)
- No wildcard imports
- Structured concurrency — see `.claude/rules/coroutines.md`

## Signing Keystores

`.signing/{debug,release}.keystore`, backups at `~/backups/rouse-context/`.

NEVER regenerate. Regenerating forces an uninstall on every device (losing app data) and burns ACME quota. Critical.

## Domain

`rousecontext.com` registered at Squarespace, nameservers on Cloudflare. Each device gets a per-subdomain wildcard cert from GTS via DNS-01 (Cloudflare API). Per-integration secrets prefix the subdomain at the SNI layer (e.g. `<integration>-<rand>.<device>.rousecontext.com`).
