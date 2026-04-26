# Agent quickstart

Distilled commands and paths agents reach for repeatedly. Pre-loading saves grep-then-discover cycles.

## JSON construction (mandatory)

Tools that return structured data MUST use `@Serializable` data classes plus `Json.encodeToString` (or `Json.encodeToJsonElement` if a tree is preferable). Manual template literals — `"""{"field":"$value"}"""` — are NOT permitted in production code.

### Why

`#417` shipped malformed JSON to user testing because a template-literal error helper interpolated a raw exception message containing quotes, producing output the connector proxy refused to parse. The `@Serializable` + `encodeToString` path escapes embedded quotes / backslashes / control characters automatically; the manual path does not.

### Two enforcement gates

1. **Test harness** — `McpToolTestHarness` at `integrations/src/test/kotlin/com/rousecontext/integrations/testing/` parse-asserts every `CallToolResult` body returned through it. Any tool whose result body fails `Json.parseToJsonElement` causes the test to fail with the offending tool name and body content.
2. **Static check** — `scripts/check-no-manual-json.sh` runs in the Android CI workflow. Greps for `"""{` in `*/src/main/*.kt` and fails the build on unallowlisted matches.

### The `// allow-manual-json` marker

For the rare case where a template literal is genuinely safe (e.g. a `@Preview`-only Compose fixture with hard-coded literals that never see runtime input), append a marker comment to the line:

```kotlin
val sample = """{"steps":7000}""" // allow-manual-json: @Preview fixture, no interpolation
```

The reason text after the colon should describe **why this isn't a runtime risk** — typically because no user-controlled value flows into the template. A reviewer should treat the absence of a clear reason as a blocker.

`AuditDetailScreen.kt` is implicitly allowlisted in the script because its preview fixtures exist purely for IDE rendering; new files should use the inline marker rather than expanding the file allowlist.

### Test harness usage

When writing or refactoring an `*McpProvider*Test`, use `McpToolTestHarness` rather than hand-rolling the mockk capture pattern:

```kotlin
private val harness = McpToolTestHarness()
private val fakeConnection: ClientConnection = mockk(relaxed = true)

@Before fun setUp() {
    val server = harness.createMockServer()
    MyMcpProvider(...).register(server)
}

@Test fun `tool does the thing`() = runBlocking {
    val result = harness.callTool(
        name = "my_tool",
        arguments = buildJsonObject { put("x", JsonPrimitive(1)) },
        connection = fakeConnection
    )
    // Body is guaranteed JSON-parseable; assert further as needed.
}
```

Pass `expectJsonBody = false` only for tools whose contract specifies a plain-text body (rare). Document the reason inline if you do.

## Module → path map

```
:app                       app/
:core:tunnel               core/tunnel/
:core:mcp                  core/mcp/
:core:bridge               core/bridge/
:core:testfixtures         core/testfixtures/
:api                       api/
:integrations              integrations/
:notifications             notifications/
:work                      work/
:device-tests              device-tests/
:e2e                       e2e/
relay (Rust)               relay/
```

Source-of-truth for the module list is `settings.gradle.kts:25-35`.

## Build & test recipes

All Gradle commands need `JAVA_HOME=/usr/lib/jvm/java-21-openjdk`.

```bash
# Build a debug APK
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# Build a release APK
./gradlew :app:assembleRelease
# → app/build/outputs/apk/release/app-release.apk

# Unit tests for one module
./gradlew :core:mcp:jvmTest                       # KMP/JVM
./gradlew :app:testDebugUnitTest                  # Android-only
./gradlew :integrations:testDebugUnitTest         # Android-only

# Tunnel integration tests (against real relay binary)
./gradlew :core:tunnel:integrationTest --tests '*OAuthEndToEndTest*'

# E2E (against a real device — requires adb host + serial)
./gradlew :e2e:e2eTest -Dadb.host=adolin -Dadb.serial=1B151FDEE008EY

# Lint & analysis (whole tree)
./gradlew ktlintCheck detekt

# Format
./gradlew ktlintFormat
```

## Relay (Rust)

```bash
cd relay
cargo test
cargo clippy --all-targets --all-features -- -D warnings
cargo fmt
```

NEVER build the relay on the GCP VPS. The PR-to-main triggers `.github/workflows/relay-deploy.yml` (or similar) which cross-compiles + deploys.

## GitHub CLI patterns

```bash
# Concise CI status
gh pr checks NNN --json name,state --jq '.[] | "\(.name)\t\(.state)"'

# Open PR
gh pr create --title "..." --body "..."

# Merge + delete branch (squash)
gh pr merge NNN --squash --delete-branch

# Issue management
gh issue create --title "..." --body "..." --label in-progress
gh issue edit NNN --add-label in-progress
gh issue edit NNN --remove-label in-progress
gh issue close NNN --comment "..."

# Just the body of a comment thread
gh issue view NNN --comments --json comments --jq '.comments[].body'
```

## Local-branch-deletion gotcha

If `gh pr merge --delete-branch` fails because another worktree has the branch checked out:

```bash
cd /home/jmonk/git/rouse-context && git checkout --detach origin/main && git branch -d <branch>
```

## adb via SSH (test device)

```bash
# adolin Pixel 6 Pro (agent-controlled)
ADB="ANDROID_SERIAL=1B151FDEE008EY /opt/android-sdk/platform-tools/adb"
ssh adolin "$ADB <command>"

# Common operations
ssh adolin "$ADB shell pidof com.rousecontext.debug"
ssh adolin "$ADB shell pm clear com.rousecontext.debug"
ssh adolin "$ADB shell am start -n com.rousecontext.debug/com.rousecontext.app.MainActivity"
ssh adolin "$ADB shell run-as com.rousecontext.debug ls files/"
ssh adolin "$ADB logcat -d -s 'Onboarding:*' 'TunnelClient:*' '*:S'"   # filter at logcat level

# Kill app cleanly (NOT am force-stop — that disables FCM until manual relaunch).
# Use cmd activity stop-app: kills the process including foreground services
# but leaves FCM reachable. See #394.
ssh adolin "$ADB shell cmd activity stop-app com.rousecontext.debug"

# Install
scp app-debug.apk adolin:/tmp/
ssh adolin "$ADB install -r /tmp/app-debug.apk"
```

## UI automation on device

Drive Android UI via `uiautomator dump` XML, NOT screencap PNGs (resolution blows up context). See `/tmp/drive_onboard.sh` for the pattern (extract bounds via regex on `text="..."[^>]*bounds="[x1,y1][x2,y2]"`).

## Relay over gcloud

```bash
gcloud compute ssh relay --zone=us-central1-a --command='sudo journalctl -u rouse-relay -f'
gcloud compute ssh relay --zone=us-central1-a --command='systemctl status rouse-relay | head -5'
```

## Common files to know

- `core/tunnel/src/jvmMain/.../OnboardingFlow.kt` — canonical onboarding sequence
- `app/src/main/.../OnboardingViewModel.kt` — canonical VM (post-#392 single-VM invariant)
- `app/src/main/.../navigation/AppNavigation.kt` + `Routes.kt` — nav graph + routes
- `core/tunnel/src/jvmMain/.../TunnelClientImpl.kt` — tunnel client
- `core/tunnel/src/jvmMain/.../MuxFrame.kt` / `MuxDemux.kt` — mux protocol
- `relay/src/api/` — relay HTTP/WS endpoints
- `relay/src/passthrough.rs` — TLS-passthrough decision + FCM wake
- `relay/src/rate_limit.rs` — rate limiters (FcmWakeThrottle, etc.)
- `docs/design/relay-api.md` — endpoint catalog (kept current as of #410)
- `docs/design/android-app.md` — module map + dep graph (kept current as of #408)
- `docs/design/overall.md` — system overview (kept current as of #409)
- `docs/ux-decisions.md` — UX flow decisions log

## When in doubt

- Code is the source of truth over docs.
- A `.claude/rules/*` rule is the source of truth over a memory entry.
- The user's most recent in-session direction overrides everything else.
