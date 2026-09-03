# Rouse Context Test Coverage Audit

**Original audit:** 2026-04-06
**Inventory re-verified:** 2026-09-02, against `61c84b93`
**Scope:** Android app + Rust relay test suite

## How to read this document

This document has two halves and they carry different warranties.

**[Test Inventory by Module](#test-inventory-by-module) is current.** Every count was
produced by the command printed next to it, and every test class it names was confirmed
to exist at `61c84b93`. Re-run the commands to re-verify.

**Everything from [Gap Analysis](#gap-analysis-production-bugs-vs-test-coverage-2026-04-06-snapshot)
onward is the 2026-04-06 snapshot, kept as history.** It records which of thirteen
then-current production bugs the suite could have caught *at that time*. Many of those
gaps have since been closed; where that is so the entry says so inline. Do not read that
half as a statement of what is untested today.

**Why the rows went bad, since the two causes want different fixes.** Most of them went
*stale*: a cleanup wave in April 2026 deleted the code they described —
`NotificationAdapter` and its test as dead code (#125), the `:integration-tests` module,
the `:health` module folded into `:integrations`, the wake endpoint (#303) — and the
document was never updated. That is ordinary drift, and the counts-plus-command shape
below is the fix for it. One row failed differently and is the reason for #654:
`TokenStoreTest` was *accurate*, stayed accurate, and still supported a false inference
about what ships. Staleness a reader can suspect from a date; that one they cannot.

**A `✓` in the inventory means the named test class exists and is green. It does not mean
its subject ships.** Those are different questions, and a coverage audit that runs them
together credits production with coverage it does not have — that defect is
[#654](https://github.com/Monkopedia/rouse-context/issues/654), and it is what this
revision repairs. The **Subject ships?** column is the second axis: it answers whether the
class under test is reachable from a `main` source set, and it is the column a reader
should look at before concluding that a shipped behaviour is covered.

**The inventory is deliberately not a full enumeration.** The tree holds 288 Kotlin test
classes and 21 Rust integration test files. Listing all of them by hand is precisely what
drifted this document out of date the first time, so each module section gives a verified
count plus the command that reproduces it, and names only the classes the rest of this
document cites or that pin a load-bearing property.

---

## Test Inventory by Module

Counts at `61c84b93`:

```bash
for m in core/tunnel core/mcp core/bridge core/testfixtures api app \
         notifications work integrations device-tests e2e; do
  printf '%s: %s\n' "$m" "$(find "$m" -path '*/src/*' -name '*Test.kt' | wc -l)"
done
ls relay/tests/*.rs | grep -v test_helpers | wc -l
```

| Module | Kotlin test classes |
|---|---|
| `core/tunnel` | 54 |
| `core/mcp` | 35 |
| `core/bridge` | 4 |
| `core/testfixtures` | 1 |
| `api` | 3 |
| `app` | 123 |
| `notifications` | 15 |
| `work` | 20 |
| `integrations` | 29 |
| `device-tests` | 1 |
| `e2e` | 3 |
| **Total** | **288** |

`relay/tests/` holds **21** integration test files, excluding the shared `test_helpers.rs`.

### 1. Core Tunnel (`core/tunnel/src/jvmTest/`) — 54 test classes

| Test Class | Covers | Subject ships? | Status |
|---|---|---|---|
| `TunnelClientImplTest` | State transitions, FCM token, session handling | Yes — `TunnelClientImpl` is bound in `app/src/main/java/com/rousecontext/app/di/AppModule.kt` | ✓ |
| `ConnectionStateMachineTest` | Legal and illegal transitions; same-state transition returns `false` rather than throwing; concurrent transitions are atomic | Yes — `core/tunnel/src/jvmMain/kotlin/com/rousecontext/tunnel/ConnectionStateMachine.kt` | ✓ |
| `TlsAcceptTest` | TLS handshake over `MuxStream` | Yes | ✓ |
| `TlsAcceptorSplitRecordTest` | A TLS record split across two DATA frames, in both the handshake and the application-data direction | Yes | ✓ |
| `WebSocketMuxTest` | WebSocket → mux frame conversion | Yes | ✓ |
| `MuxFrameTest` | Frame encoding/decoding | Yes | ✓ |
| `MuxDemuxTest` | Demux logic | Yes | ✓ |
| `OnboardingFlowTest` | Onboarding workflow | Yes — `OnboardingFlow` is bound in `AppModule.kt` | ✓ |
| `EndToEndSessionTest` | Real relay binary, real TLS handshake | Yes | ✓ |
| `OAuthEndToEndTest` | OAuth flow through the tunnel | Yes | ✓ |
| `RealRelayIntegrationTest` | Raw WebSocket integration against a running relay | Yes | ✓ |

Corrections made in this revision:

- A row for `TunnelConnectionStateMachineTest` was removed: no such path exists at
  `61c84b93`, and `git log --all -- '*TunnelConnectionStateMachine*'` is empty, so it has
  never been a file. Its only occurrence in the whole history is `6e1c3cfe`, the commit
  that added this document. The real class is `ConnectionStateMachineTest`, listed above.
- `MuxFrameTest` and `MuxDemuxTest` were tagged "(common)". Both live in `jvmTest`. There
  is no `commonMain` or `commonTest` anywhere in the tree — `docs/design/overall.md:506`
  states this outright for `:core:mcp` and `:core:tunnel`.

**Still open:**
- `TunnelClientImplTest` drives `MuxCodec` frames by hand rather than a real TLS handshake.
  The real-handshake coverage lives in `EndToEndSessionTest` and `TlsAcceptorSplitRecordTest`.

**Closed since the 2026-04-06 audit:**
- Multi-record TLS data frames → `TlsAcceptorSplitRecordTest`.
- State-machine idempotency and concurrent transitions → `ConnectionStateMachineTest`
  (`transitionToSameStateReturnsFalse`, `concurrentTransitionsAreAtomic`).
- Disconnect/reconnect cycles → `core/tunnel`'s `AbruptDisconnectTest` and
  `HalfOpenDetectionTest`; `app`'s `HalfOpenReconnectTest` and `RapidFcmWakesTest`.
- FCM token send around reconnect → `work`'s `FcmTokenRegistrarTest`, which pins that a
  token send is skipped while `DISCONNECTED`/`CONNECTING` and forwarded once
  `CONNECTED`/`ACTIVE`, and that cancellation propagates rather than being swallowed.

### 2. Core MCP (`core/mcp/src/jvmTest/`) — 35 test classes

| Test Class | Covers | Subject ships? | Status |
|---|---|---|---|
| `McpProtocolTest` | `tools/call`, `resources/list`, concurrent requests, audit logging | Yes — `McpSession` is bound in `AppModule.kt` | ✓ |
| `HttpRoutingTest` | OAuth metadata, path routing | Yes | ✓ |
| `McpSessionTest` | HTTP POST, token auth, tool execution | Yes | ✓ |
| `McpResponseFormatTest` | No explicit `null` in `tools/call`, `tools/list`, `initialize` responses | Yes | ✓ |
| `AuthorizationCodeFlowTest` | Authorization-code PKCE flow | Yes | ✓ |
| `DeviceCodeFlowTest` | Device-code flow | Yes | ✓ |
| `AuthMiddlewareTest` | Bearer token validation | Yes | ✓ |
| `RateLimiterTest` | Rate-limit enforcement | Yes | ✓ |
| `ErrorResponseTest` | Error handling | Yes | ✓ |
| `AuthPageCspTest` | CSP header permitting inline styles, `X-Frame-Options: DENY`, HSTS, HTML body | Yes | ✓ |
| `ConcurrentToolCallTest` | Many rapid calls to one tool, interleaved calls across tools, independence across clients | Yes | ✓ |
| `AuthPageGalleryTest` | Emits static HTML variants for eyeballing | Yes, but the test asserts nothing about rendering | ⚠ Limited |
| `TokenStoreTest` | The `TokenStore` **contract** — issue, validate, expire, revoke — exercised against `InMemoryTokenStore` only | **No.** Production binds `RoomTokenStore` (`AppModule.kt:282`, `singleOf(::RoomTokenStore) bind TokenStore::class`). `InMemoryTokenStore` reaches a `main` source set only as an unused default argument in `DeviceCodeManager.kt:50` and `AuthorizationCodeManager.kt:77`, and `McpSession.kt:36-38` always passes its own injected store instead. **The shipped store is covered by `RoomTokenStoreTest` (§4), not by this class.** | ✓ as a contract test |

The `TokenStoreTest` row is the reason this document needed a **Subject ships?** column. As
originally written — "`TokenStoreTest` | Token storage and expiry | ✓ Good" — every word was
true and the inference a reader drew from it was false: it read as coverage of the storage
path the app actually runs, which is Room-backed and which that class never touches.

**Still open:**
- No browser-rendered test of the auth page. `AuthPageCspTest` pins the headers that the
  original bug was about; nothing renders the page in a real engine.
- No test for a malformed MCP request — the protocol tests all send valid JSON-RPC.
- No test for token-expiry races: revocation concurrent with refresh, double refresh.
- No test for an unreachable OAuth metadata endpoint.

**Closed since the 2026-04-06 audit:**
- Explicit nulls in responses → `McpResponseFormatTest`.
- CSP headers on the auth page → `AuthPageCspTest`.

### 3. Core Bridge (`core/bridge/src/jvmTest/`) — 4 test classes

| Test Class | Covers | Subject ships? | Status |
|---|---|---|---|
| `SessionHandlerTest` | Session routing and cleanup | Yes — `SessionHandler` is bound in `AppModule.kt` | ✓ |
| `SessionHandlerDefectVisibilityTest` | An unhandled TLS state is surfaced rather than ending quietly, in both directions; an ordinary peer disconnect and a plain `IOException` stay quiet EOFs; cancellation propagates | Yes | ✓ |
| `ClientPassthroughTest` | Client-to-device passthrough with a tool call; sequential requests on one session; **concurrent passthrough sessions are independent** | Yes | ✓ |
| `HttpHeaderInjectorTest` | Header injection across chunked reads, keep-alive requests, and bodies; byte-count conservation | Yes — `HttpHeaderInjector` is called from `core/bridge/src/jvmMain/.../SessionHandler.kt` | ✓ |

The count in this section has been wrong twice in opposite directions. It read "2 test
classes" while there were five; #684 then deleted `TunnelSessionManagerTest` and
`TunnelSessionManagerDefectVisibilityTest` along with their subject and corrected the
figure to "1 test class", which under-counted the remaining four. It is four.

The old "no tests for **concurrent session operations**" gap no longer holds:
`ClientPassthroughTest` has `concurrent passthrough sessions are independent`. Removed.

**Still open:**
- No test for cleanup after an unexpected mid-session disconnect.

### 4. App (`app/src/test/`, plus `testFoss/` and `testGoogle/`) — 123 test classes

| Test Class | Covers | Subject ships? | Status |
|---|---|---|---|
| `MainDashboardViewModelTest` | State flow, integration visibility, audit entries | Yes | ✓ |
| `DashboardStateFlowTest` | The dashboard reacts to enable/disable and to audit inserts without a manual refresh | Yes | ✓ |
| `SettingsViewModelTest` | Settings state | Yes | ✓ |
| `AuditHistoryViewModelTest` | Audit list and filtering | Yes | ✓ |
| `AuthorizationApprovalViewModelTest` | Auth-approval UI state | Yes | ✓ |
| `AuthApprovalReceiverTest` | The approval `BroadcastReceiver` | Yes — instantiated by the manifest | ✓ |
| `RoomTokenStoreTest` | **The token store the app actually binds**: refresh-token family rotation and reuse revocation, against a real Room database | Yes — `AppModule.kt:282` | ✓ |
| `OAuthDeviceFlowIntegrationTest` | The full authorization-code flow end to end through the real `RoomTokenStore` and `AuthorizationCodeManager` | Yes | ✓ |
| `ToolCallViaSniPassthroughTest` | A tool call arriving over SNI passthrough | Partly — `SessionHandler` and `CertificateStore` are production; the `TunnelClientImpl` is built with a fixture WebSocket factory. Its kdoc enumerates which is which. | ✓ |
| `ScreenScreenshotTest` | Renders every screen to PNG | Yes, but the test asserts nothing about what it renders | ⚠ Limited |

`RoomTokenStoreTest` had never been listed here — `git log -S'RoomTokenStoreTest' --
docs/test-coverage-audit.md` returns only the commit that added this paragraph — while
`TokenStoreTest` — which does not touch the shipped store — was listed as good coverage of
"token storage and expiry". The document under-reported the real coverage and over-reported
the notional coverage of the same behaviour.

**Still open (the 2026-04-06 HIGH-PRIORITY list, re-checked):**
- No assertion that design-system colours, typography, or spacing follow Material 3.
- No test for status-bar icon colour — Robolectric cannot observe it.
- No test counting `TopAppBar` instances per screen (the double-app-bar bug).
- `ScreenScreenshotTest` and the other screenshot tests capture but do not assert, in
  either theme.

**Closed since the 2026-04-06 audit:**
- Connection status not reaching the dashboard → `DashboardStateFlowTest`.
- Navigation state → `BackStackFlowTest`, `NavigationBarVisibilityTest`,
  `IntegrationEnabledAutoNavTest`.
- Hard-coded integration URLs → `IntegrationUrlTest`, `UrlBuilderTest`,
  `OAuthHostnameProviderTest`, `IntegrationIdConsistencyTest`.

### 5. Notifications (`notifications/src/test/`) — 15 test classes

| Test Class | Covers | Subject ships? | Status |
|---|---|---|---|
| `NotificationChannelsTest` | Channel creation and importance | Yes — `NotificationChannels` is an object used from `main` | ✓ |
| `NotificationIconTest` | Every notifier posts with the `ic_stat_rouse` small icon: foreground, FGS-limit, security alert and info, auth request | Yes | ✓ |
| `AuthRequestNotifierTest` | Auth-request notification | Yes | ✓ |
| `SecurityCheckNotifierTest` | Security-check alert and info notifications | Yes | ✓ |
| `SessionSummaryNotifierTest` | End-of-session summary | Yes | ✓ |
| `PerToolCallNotifierTest` | Per-tool-call notifications | Yes | ✓ |
| `AuditDaoTest` | Audit database persistence | Yes | ✓ |
| `AuditMigrationTest` | Room schema migration for the audit database | Yes | ✓ |
| `NotificationScreenshotTest` | Renders notification previews | Yes, but asserts nothing | ⚠ Limited |

A row for `NotificationAdapterTest` was removed: neither it nor its subject exists at
`61c84b93`. Both were real. `notifications/src/main/java/.../NotificationAdapter.kt` landed
in `52276b1e` (2026-04-05) as production code and its test in `2325e064` (2026-04-07); both
were deleted on 2026-04-14 by `7a54bdce`, "Delete NotificationAdapter and NotificationAction
dead code (#125)", when the notifier pattern replaced them. So this row was not fictional
when written — it went stale, along with the code it described. `docs/workflow.md` still
lists it in the "Files to create" list of task plan T-7, on the line reading `— maps
NotificationAction to Android NotificationManager` (grep that text; the line number has
moved three times and citing it here has already gone stale once). That is a record of a
plan that was carried out and later reversed, not a claim about today's architecture, and
it is left alone here — it belongs to
[#687](https://github.com/Monkopedia/rouse-context/issues/687).

A `NotificationDaoTest` row also sat in this section. That class is real but lives in
`integrations/src/test/java/com/rousecontext/integrations/notifications/`, not in this
module; it is listed under §8 instead.

**Still open:**
- No verification of notification rendering on a real device — Robolectric shadows do not
  reproduce system rendering.
- No test across Android API levels.
- No test of notification accessibility attributes.

**Closed since the 2026-04-06 audit:**
- Small-icon resource id → `NotificationIconTest`, which pins `ic_stat_rouse` at five
  posting sites.

### 6. Work (`work/src/test/`) — 20 test classes

| Test Class | Covers | Subject ships? | Status |
|---|---|---|---|
| `FcmDispatchTest` | FCM message routing | Yes — `FcmDispatch` is an object called from `main` | ✓ |
| `FcmTokenRegistrarTest` | Token forwarded only in `CONNECTED`/`ACTIVE`; suppressed in `DISCONNECTED`/`CONNECTING`; cancellation propagates | Yes | ✓ |
| `WakeReconnectDeciderTest` | The wake decision per tunnel state, including a stale `ACTIVE` socket and a throwing health check | Yes | ✓ |
| `CertRenewalWorkerTest` | Cert renewal task | Yes — constructed in `work/src/main/kotlin/com/rousecontext/work/KoinWorkerFactory.kt:41` | ✓ |
| `SecurityCheckWorkerTest` | Security checks | Yes — constructed in `KoinWorkerFactory.kt:32` | ✓ |
| `CertRenewalSchedulerTest` / `SecurityCheckSchedulerTest` | WorkManager enqueue and constraints | Yes | ✓ |
| `TunnelForegroundServiceLifecycleTest` | Foreground-service start/stop lifecycle | Yes | ✓ |
| `TunnelBoundaryFailureReportingTest` | A session failure becomes a crash report, and **one bad session does not stop the collector**, driven through the real Koin graph | Yes — this is the collector that ships | ✓ |
| `GracefulTunnelShutdownTest` | Ordered shutdown | Yes | ✓ |
| `IdleTimeoutTest` | Idle-timeout behaviour | Yes — `IdleTimeoutManager` is bound in `AppModule.kt` | ⚠ Partial |
| `WakelockManagerTest` | Wakelock acquire/release | Yes — bound in `AppModule.kt` | ⚠ Partial |
| `DataStoreSpuriousWakeRecorderTest` | Spurious-wake accounting | Yes | ✓ |

**Closed since the 2026-04-06 audit:**
- Work scheduling was listed as untested → `CertRenewalSchedulerTest`,
  `SecurityCheckSchedulerTest`, `WorkManagerFactoryIntegrationTest`, and
  `app`'s `CertRenewalWorkerSchedulingTest`.
- Rapid successive wakes → `app`'s `RapidFcmWakesTest` plus `WakeReconnectDeciderTest`.
- FCM token failure handling → `FcmTokenRegistrarTest`.

### 7. Relay (`relay/tests/`) — 21 integration test files

| Test File | Covers | Status |
|---|---|---|
| `integration_test.rs` | Full WebSocket connection, mux frames | ✓ |
| `passthrough_test.rs` | Stream passthrough | ✓ |
| `mux_lifecycle_test.rs` | Stream open/close lifecycle | ✓ |
| `mux_frame_test.rs` | Frame encoding/decoding | ✓ |
| `config_test.rs` | Config parsing and env overrides | ✓ |
| `api_register_test.rs` | Client registration endpoint | ✓ |
| `api_register_identity_error_shape_test.rs` | Registration error response shape | ✓ |
| `api_status_test.rs` | Status endpoint | ✓ |
| `api_renew_test.rs` | Cert renewal endpoint | ⚠ Limited |
| `api_rotate_secret_test.rs` | Per-integration secret rotation | ✓ |
| `acme_eab_integration_test.rs` | ACME external account binding | ✓ |
| `sni_test.rs` | SNI extraction | ✓ |
| `subdomain_test.rs` / `request_subdomain_test.rs` | Subdomain validation and per-request resolution | ✓ |
| `router_auth_split_test.rs` | Authenticated vs unauthenticated route split | ✓ |
| `rate_limit_test.rs` | Rate limiting | ✓ |
| `valid_secrets_cache_test.rs` | Secret cache invalidation | ✓ |
| `maintenance_test.rs` / `maintenance_loop_test.rs` | Device maintenance loop | ✓ |
| `crash_test.rs` | Crash-report intake | ✓ |
| `shutdown_test.rs` | Clean shutdown | ✓ |

A row for `api_wake_test.rs` was removed: there is no such file in `relay/tests/` at
`61c84b93`. There was. It landed in `0cf23dc2` (2026-04-05) and was deleted on 2026-04-19
by `1993b794`, "Fix #303: Remove dead wake endpoint module (#310)", together with the
endpoint it tested. `docs/workflow.md`'s task plan T-4 still lists it twice: under "Tests
first" on the `api_wake_test.rs` line reading `online device returns 200 immediately`, and
under "Files to create" as `relay/tests/api_wake_test.rs` (grep those rather than line
numbers — the numbers this sentence used to carry have already gone stale once).
Wake-related behaviour that survives is exercised inside `integration_test.rs`,
`api_status_test.rs` and `rate_limit_test.rs`.

**Still open:**
- No relay-side test for a TLS record spanning multiple WebSocket frames. The client side
  is covered by `TlsAcceptorSplitRecordTest`; the relay splices bytes without parsing them,
  so this is a lower-value gap than it looked in April, but it is not pinned.
- No negative test for corrupted or truncated frames.
- No test that `max_streams_per_device` is enforced.

### 8. Integrations (`integrations/src/test/`) — 29 test classes

The 2026-04-06 revision split this across sections headed "Health Connect
(`health/src/test/`)" and "Outreach, Usage". `settings.gradle.kts` declares no `:health`
module at `61c84b93` — it declares `:integrations`, which holds all three providers. It
once did: `include(":health")` was added in `65d71fba` (2026-04-05) and dropped on
2026-04-14 by `f198df6b`, "Fix #124: Wire `:integrations`, drop old modules", which moved
`health/` to `integrations/health/`. The old headings describe where those tests used to
live.

| Test Class | Covers | Subject ships? | Status |
|---|---|---|---|
| `HealthConnectMcpServerTest` | Tool registration and calls | Yes — constructed in `app/src/main/java/com/rousecontext/app/registry/HealthConnectIntegration.kt:24` | ✓ |
| `RecordTypeRegistryTest` | Health record-type mapping | Yes — `RecordTypeRegistry` is an object used from `main` | ✓ |
| `RealHealthConnectRepositoryTest` | The repository against the Health Connect client | Yes | ✓ |
| Query suites (`ActivityQueriesTest`, `BodyQueriesTest`, `SleepQueriesTest`, `VitalsQueriesTest`, `NutritionQueriesTest`, `MindfulnessQueriesTest`, `ReproductiveQueriesTest`, `BucketAggregationTest`) | Per-domain Health Connect queries and bucketing | Yes | ✓ |
| `NotificationDaoTest` | Notification capture persistence | Yes | ✓ |
| `NotificationCaptureServiceTest` | The capture `NotificationListenerService` | Yes | ✓ |
| `NotificationMcpProviderToolsTest` / `NotificationMcpToolExecutionTest` | Notification provider tool surface and execution | Yes | ✓ |
| `OutreachMcpProviderTest`, `OutreachChannelIdResolutionTest`, `OutreachQueryInstalledAppsTest`, `OutreachJsonShapeTest` | Outreach provider | Yes | ✓ |
| `UsageMcpProviderTest`, `UsageJsonShapeTest`, `ParsePeriodTest` | Usage provider | Yes | ✓ |
| `IntegrationOrthogonalityTest`, `ToolsListSizeTest`, `AllToolsHumanizerValidationTest` | Cross-provider invariants: providers do not collide, the tool list size is pinned, every tool name humanises | Yes | ✓ |

**Still open:**
- No test for a Health Connect permission denial part-way through a query.

### 9. API, device, end-to-end, and fixtures

| Module | Test Class | Covers | Status |
|---|---|---|---|
| `api` | `IntegrationStateStoreTest` | Enable/disable persists; `observe` emits | ✓ |
| `api` | `IntegrationStateTest` | Available/Disabled/Pending/Active/Unavailable derivation | ✓ |
| `api` | `CrashReporterTest` | Crash-reporter contract | ✓ |
| `device-tests` | `DeviceIntegrationTest` | End-to-end device flow against a real relay | ✓ |
| `e2e` | `ColdStartEndToEndTest`, `McpEndToEndTest`, `IntegrationToolsTest` | Cold start through push, MCP round trip, tool surface | ✓ |
| `core/testfixtures` | `IntegrationHttpSupportTest` | The shared HTTP fixture itself | ✓ |

A row for `TunnelRelayIntegrationTest` was removed from this section: no such class exists
at `61c84b93`. It did exist — 360 lines at
`integration-tests/src/test/kotlin/com/rousecontext/tunnel/integration/TunnelRelayIntegrationTest.kt`,
added in `51f99bdf` (2026-04-05) and deleted on 2026-04-14 by `0f35f265`, "Remove orphaned
`:integration-tests` module", along with the module that held it. Tunnel-plus-relay
integration is now covered by `core/tunnel`'s `RealRelayIntegrationTest` and
`EndToEndSessionTest`, and by `device-tests`' `DeviceIntegrationTest`.

---

## Gap Analysis: Production Bugs vs. Test Coverage (2026-04-06 snapshot)

> **Everything from here to the end of the document is the 2026-04-06 snapshot.** It is
> kept as a record of what the suite looked like when thirteen production bugs slipped
> through, and of what was proposed in response. It is *not* a current statement of
> coverage. Entries whose gap has since been closed are annotated inline with the test
> that closed them; the annotations were verified against `61c84b93`, the surrounding
> prose was not rewritten. For current coverage read
> [Test Inventory by Module](#test-inventory-by-module) instead.


### Bug 1: TLS Handshake Fails on Multi-Record Data Frames

**Symptom:** "TLS handshake failure" after successful WebSocket connection  
**Root Cause:** TLS record may span multiple WebSocket frames; decoder doesn't handle this  
**Should be tested by:** 
- ✗ No test in `TlsAcceptTest` for multi-record frames
- ✗ No test in relay `mux_frame_test.rs` for frame boundary conditions
- ✗ EndToEndSessionTest uses real handshake but doesn't deliberately send split frames

**Test Location:** `core/tunnel/src/jvmTest/kotlin/com/rousecontext/tunnel/TlsAcceptTest.kt` (add test)  
**Test Location:** `relay/tests/mux_frame_test.rs` (add test)

> **Closed on the client side.** `TlsAcceptorSplitRecordTest` pins a TLS record split
> across two DATA frames in both the handshake and the application-data direction. The
> relay-side frame-boundary test does not exist at `61c84b93` — no file in `relay/tests/`
> mentions record splitting. The relay splices bytes without parsing TLS, so it is a
> lower-value gap than it looked here.

### Bug 2: OAuth Auth Page Renders with Broken Styles

**Symptom:** Styles not applied on auth page (CSP blocking inline <style> in some clients)  
**Root Cause:** AuthPageGalleryTest generates static HTML but doesn't verify CSP headers or test in browser  
**Should be tested by:**
- ✗ No test verifies CSP headers on auth page response
- ✗ AuthPageGalleryTest generates HTML but doesn't verify it renders with styles
- ✗ No browser automation test (Puppeteer, Playwright, Selenium)

**Test Location:** `core/mcp/src/jvmTest/kotlin/com/rousecontext/mcp/core/AuthPageGalleryTest.kt` (expand)

> **Partly closed.** `AuthPageCspTest` pins the CSP header that permits inline styles,
> plus `X-Frame-Options: DENY` and HSTS. No browser-rendered test exists.

### Bug 3: Notification Icons Not Rendering Correctly

**Symptom:** Notification icon shows as blank or wrong color  
**Root Cause:** Small icon resource ID incorrect or missing; color field not set  
**Should be tested by:**
- ✗ No test verified the small icon id on any notifier
- ✗ No test verified the icon is a valid drawable resource
- ✗ No test on an actual device (Robolectric shadows don't fully simulate system rendering)

> **Closed.** `notifications/src/test/java/com/rousecontext/notifications/NotificationIconTest.kt`
> pins `ic_stat_rouse` as the small icon at five posting sites: the foreground notifier,
> the FGS-limit notifier, the security-check alert and info notifiers, and the
> auth-request notifier. The device-rendering gap remains open.
>
> The `NotificationAdapterTest` this entry originally named was real when it was written
> and is gone now: `7a54bdce` deleted it and its subject as dead code on 2026-04-14
> (#125). The bullets above are reworded to name the property rather than the vanished
> class.

### Bug 4: Hardcoded URLs Not Caught

**Symptom:** Integration URLs point to wrong subdomain or staging endpoint  
**Root Cause:** No test verifies the hostname parameter flows through to OAuth metadata and MCP endpoints  
**Should be tested by:**
- ✗ McpProtocolTest uses `test.rousecontext.com` but doesn't verify subdomain from device cert
- ✗ HttpRoutingTest doesn't verify `hostname` parameter is used in all OAuth endpoints
- ✗ No test for production hostname overrides

**Test Location:** `core/mcp/src/jvmTest/kotlin/com/rousecontext/mcp/core/HttpRoutingTest.kt` (expand)  
**Test Location:** `core/mcp/src/jvmTest/kotlin/com/rousecontext/mcp/core/McpSessionTest.kt` (add test)

> **Closed on the app side.** `UrlBuilderTest` and `IntegrationUrlTest` pin that the MCP
> URL is built from the real subdomain and the per-integration secret and is empty when
> registration is incomplete; `OAuthHostnameProviderTest` pins the hostname the OAuth
> metadata is served under; `IntegrationIdConsistencyTest` pins that every canonical
> integration id resolves and that unknown ids do not.

### Bug 5: Design System Not Applied to Screens

**Symptom:** Screens use hardcoded colors instead of Material Design 3 tokens  
**Root Cause:** No test verifies design system colors/typography are used  
**Should be tested by:**
- ✗ ScreenScreenshotTest captures images but doesn't assert on colors
- ✗ No test verifies Material3.colorScheme is used
- ✗ No test verifies token consistency across screens

**Test Location:** `app/src/test/java/com/rousecontext/app/ui/screenshots/ScreenScreenshotTest.kt` (add assertions)

### Bug 6: MCP Response Format (Explicit Nulls) Breaks Claude

**Symptom:** Claude cannot parse responses with explicit `null` values  
**Root Cause:** Serializer may have `explicitNulls = true` in some path, or SDK adds nulls  
**Should be tested by:**
- ✗ McpProtocolTest doesn't verify response JSON doesn't contain explicit `null` values
- ✗ No test parses actual response and checks for `"field": null`
- ✗ HttpTransport has `explicitNulls = false` but no test verifies this is honored

**Test Location:** `core/mcp/src/jvmTest/kotlin/com/rousecontext/mcp/core/McpProtocolTest.kt` (add assertion)

> **Closed.** `McpResponseFormatTest` asserts no explicit `null` appears in the
> `tools/call`, `tools/list`, or `initialize` responses.

### Bug 7: FCM Wake Throttle Too Aggressive

**Symptom:** Device won't wake again within ~5 minutes of last wake  
**Root Cause:** Throttle logic doesn't respect actual elapsed time; may use wall clock instead of timers  
**Should be tested by:**
- ✗ FcmDispatchTest doesn't test throttle behavior at all
- ✗ No test with fake clock to verify throttle timing
- ✗ No test for rapid successive wake broadcasts

**Test Location:** `work/src/test/kotlin/com/rousecontext/work/FcmDispatchTest.kt` (expand)

> **Closed.** `WakeReconnectDeciderTest` pins the wake decision per tunnel state,
> including a stale `ACTIVE` socket and a throwing health check; `app`'s
> `RapidFcmWakesTest` drives five rapid wakes and asserts the tunnel stays stable.

### Bug 8: Double App Bars on Detail Screens

**Symptom:** Two app bars visible on audit detail, integration detail screens  
**Root Cause:** Screen defines TopAppBar inside Scaffold, but navigation also adds one  
**Should be tested by:**
- ✗ ScreenScreenshotTest doesn't verify single app bar
- ✗ No test for Compose hierarchy inspection (count TopAppBar instances)

**Test Location:** `app/src/test/java/com/rousecontext/app/ui/screenshots/ScreenScreenshotTest.kt` (add test)

### Bug 9: Status Bar Icon Colors Wrong

**Symptom:** Status bar icon (WiFi, battery, signal) has wrong color or appearance  
**Root Cause:** SystemBarStyle or WindowInsetsController not configured correctly  
**Should be tested by:**
- ✗ No test verifies status bar style is set
- ✗ No test on actual device (Robolectric can't test status bar)
- ✗ No accessibility test for contrast

**Test Location:** `app/src/test/java/com/rousecontext/app/ui/viewmodels/MainDashboardViewModelTest.kt` (add test for configuration)

### Bug 10: Connection Status Not Updating

**Symptom:** Dashboard shows "Disconnected" even when tunnel is actively connected (confirmed by relay logs)  
**Root Cause:** Connection state from tunnel not being observed in ViewModel; state lags or doesn't emit  
**Should be tested by:**
- ✗ MainDashboardViewModelTest mocks IntegrationStateStore but doesn't observe tunnel state
- ✗ No test verifies connection state flows from TunnelClient to Dashboard
- ✗ No test for state emission timing or debouncing

**Test Location:** `app/src/test/java/com/rousecontext/app/ui/viewmodels/MainDashboardViewModelTest.kt` (expand)

> **Closed.** `DashboardStateFlowTest` drives `MainDashboardViewModel` through real
> enable/disable and audit-insert events and asserts the dashboard updates without a
> manual refresh.

### Bug 11: Spurious Disconnect/Reconnect Cycles

**Symptom:** WebSocket disconnects and reconnects repeatedly within seconds of connection  
**Root Cause:** Connection state machine or WebSocket error handling not properly idempotent  
**Should be tested by:**
- ✗ TunnelClientImplTest doesn't test rapid disconnect/reconnect
- ✗ No test for concurrent state transitions
- ✗ No test for state race conditions

**Test Location:** `core/tunnel/src/jvmTest/kotlin/com/rousecontext/tunnel/TunnelClientImplTest.kt` (add test)

> **Closed.** `core/tunnel`'s `AbruptDisconnectTest` and `HalfOpenDetectionTest` cover the
> transport side; `app`'s `HalfOpenReconnectTest` drives a half-open socket to
> `DISCONNECTED` through keepalive, and `RapidFcmWakesTest` asserts five rapid wakes leave
> the tunnel stable. Concurrent state transitions are pinned by
> `ConnectionStateMachineTest.concurrentTransitionsAreAtomic`.

### Bug 12: FCM Token Send Fails After Reconnect

**Symptom:** "Failed to send FCM token to relay" with JobCancellationException  
**Root Cause:** Coroutine for sending token is cancelled before completion on reconnect  
**Should be tested by:**
- ✗ TunnelClientImplTest tests sendFcmToken but not after disconnect/reconnect cycle
- ✗ No test for concurrent cancellation safety
- ✗ No test for token send on reconnect path

**Test Location:** `core/tunnel/src/jvmTest/kotlin/com/rousecontext/tunnel/TunnelClientImplTest.kt` (add test)

> **Closed.** `work`'s `FcmTokenRegistrarTest` pins that a token send is suppressed while
> `DISCONNECTED`/`CONNECTING`, forwarded once `CONNECTED`/`ACTIVE`, and that cancellation
> propagates instead of being swallowed.

### Bug 13: Invalid State Transition (DISCONNECTED -> DISCONNECTED)

**Symptom:** `IllegalStateException: Invalid transition from DISCONNECTED to DISCONNECTED`  
**Root Cause:** State machine doesn't allow idempotent disconnection  
**Should be tested by:**
- ✗ TunnelClientImplTest doesn't test idempotent disconnect
- ✗ ConnectionStateMachineTest may not cover this case

**Test Location:** `core/tunnel/src/jvmTest/kotlin/com/rousecontext/tunnel/ConnectionStateMachineTest.kt` (verify or add)

> **Closed.** `ConnectionStateMachineTest.transitionToSameStateReturnsFalse` pins that a
> same-state transition returns `false` rather than throwing, and
> `concurrentTransitionsAreAtomic` covers the concurrent case.

---

## Missing Test Categories (2026-04-06 snapshot)

### 1. Negative Tests (Malformed Input)

**Gap:** No tests for:
- Malformed JSON-RPC requests (invalid method, missing id)
- Corrupted mux frames (invalid frame type, truncated payload)
- Incomplete TLS records
- Invalid Bearer token formats
- Expired or revoked tokens

**Priority:** HIGH (security and stability)

**Where to add:**
- `core/mcp/src/jvmTest/kotlin/com/rousecontext/mcp/core/McpProtocolTest.kt`
- `relay/tests/integration_test.rs`
- `core/tunnel/src/jvmTest/kotlin/com/rousecontext/tunnel/TlsAcceptTest.kt`

### 2. Concurrency Tests

**Gap:** No tests for:
- Multiple simultaneous tool calls
- Concurrent stream open/close
- Race between token refresh and tool call
- Concurrent WebSocket reads/writes
- State transitions during in-flight requests

**Priority:** HIGH (production concurrency bugs)

**Where to add:**
- `core/mcp/src/jvmTest/kotlin/com/rousecontext/mcp/core/McpSessionTest.kt`
- `core/tunnel/src/jvmTest/kotlin/com/rousecontext/tunnel/TunnelClientImplTest.kt`
- `relay/tests/integration_test.rs`

### 3. Configuration Tests

**Gap:** No tests for:
- Wrong subdomain in certificate
- Expired certificates
- Missing OAuth endpoints
- Disabled integrations still appearing in responses
- Environment variable overrides (already in relay, missing in app)

**Priority:** MEDIUM (configuration drift)

**Where to add:**
- `core/mcp/src/jvmTest/kotlin/com/rousecontext/mcp/core/HttpRoutingTest.kt`
- `app/src/test/java/com/rousecontext/app/ui/viewmodels/SettingsViewModelTest.kt`

### 4. UI State and Navigation Tests

**Gap:** No tests for:
- Navigation state after back press
- Screen composition hierarchy (double app bars, layout inflation)
- Design system token usage (colors, typography, spacing)
- Accessibility attributes (content descriptions, contrast)
- Dark mode handling for all screens
- Hardcoded URLs in screens (onboarding, settings, etc.)

**Priority:** HIGH (user-facing bugs)

**Where to add:**
- `app/src/test/java/com/rousecontext/app/ui/screenshots/ScreenScreenshotTest.kt` (expand with assertions)
- New file: `app/src/test/java/com/rousecontext/app/ui/CompositionTest.kt`
- New file: `app/src/test/java/com/rousecontext/app/ui/DesignSystemTest.kt`

### 5. Browser Automation Tests

**Gap:** No tests for:
- OAuth page renders with styles in actual browser
- CSP headers allow inline styles
- Authorization page user flow (display code visibility, button functionality)
- Redirect URI handling in real browser

**Priority:** MEDIUM (OAuth UX)

**Where to add:**
- New file: `core/mcp/src/jvmTest/kotlin/com/rousecontext/mcp/core/AuthPageBrowserTest.kt` (Playwright or similar)

### 6. End-to-End Feature Tests

**Gap:** No tests for:
- Full onboarding flow (setup -> OAuth -> first tool call)
- Multiple integrations enabled simultaneously
- Integration enable/disable during active session
- Token refresh during active tool call
- Reconnect preserves tool call in flight

**Priority:** MEDIUM (user workflows)

**Where to add:**
- `app/src/test/java/com/rousecontext/app/ui/viewmodels/` (new feature flow tests)
- `integration-tests/src/test/kotlin/com/rousecontext/tunnel/integration/` (expand)

---

## Test Quality Issues (2026-04-06 snapshot)

### Issue 1: Mocks Hide Real Code Paths

**Problem:** MainDashboardViewModelTest, AuthorizationApprovalViewModelTest mock everything:
- `IntegrationStateStore` is mocked
- `TokenStore` is mocked
- `AuditDao` is mocked
- Actual state flow logic never executed

**Impact:** Real bugs in state composition not caught (e.g., connection status not updating)

**Fix:** Add integration tests with real (in-memory) implementations:
```kotlin
val stateStore = InMemoryIntegrationStateStore()
val tokenStore = InMemoryTokenStore()
val auditDao = InMemoryAuditDao()
val vm = MainDashboardViewModel(stateStore, tokenStore, auditDao)
// Assert actual state changes
```

### Issue 2: Manual Frame Pumping Doesn't Catch Real TLS Issues

**Problem:** TunnelClientImplTest uses `MuxCodec.encode()` to manually create frames:
```kotlin
serverWs.send(Frame.Binary(true, MuxCodec.encode(MuxFrame.Open(streamId = 7u))))
```

**Impact:** Real TLS handshake variants not tested (multi-record frames, fragmented records, etc.)

**Fix:** Use real TLS handshake from `TlsAcceptTest`, not manual mux frames:
```kotlin
// Start TLS handshake using SSLEngine (from TlsAcceptTest)
val sslEngine = createRealSSLEngine()
sslEngine.beginHandshake()
// This will generate real multi-record frames
```

### Issue 3: Screenshots Don't Assert

**Problem:** ScreenScreenshotTest captures images but doesn't verify content:
```kotlin
// Captures screenshot but doesn't assert
captureRoboImage("audit_detail_screen.png")
```

**Impact:** Visual regressions and design system violations silently pass

**Fix:** Add assertions in screenshot tests:
```kotlin
composeRule.onNodeWithText("Audit Detail").assertExists()
composeRule.onAllNodes(isInstance(TopAppBar::class)).assertCountEquals(1)
```

### Issue 4: No Assertion on Response Format

**Problem:** McpProtocolTest doesn't verify JSON doesn't contain explicit nulls:
```kotlin
val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
val result = json["result"]?.jsonObject
// No check: assertFalse(result.containsKey("nullField") && result["nullField"] is JsonNull)
```

**Impact:** Explicit nulls in responses slip through undetected

**Fix:** Add assertion:
```kotlin
// Verify no explicit nulls in response
result?.keys?.forEach { key ->
    assertNotNull("Field $key should not be null", result[key])
}
```

### Issue 5: Hardcoded Test Values Drift from Production

**Problem:** Tests use hardcoded values that diverge from production:
- `validChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"` (fixed string)
- `hostname = "test.rousecontext.com"` (test domain)
- `defaultRedirectUri = "http://localhost:3000/callback"` (not production)

**Impact:** Production URLs and formats not validated

**Fix:** Use configurable test fixtures or environment-driven tests:
```kotlin
val productionHostname = System.getenv("RELAY_HOSTNAME") ?: "relay.rousecontext.com"
```

---

## Recommended New Tests (2026-04-06 snapshot)

### HIGH PRIORITY (Caused Production Bugs)

#### 1. Multi-Record TLS Frame Handling

**File:** `core/tunnel/src/jvmTest/kotlin/com/rousecontext/tunnel/TlsAcceptTest.kt`

**Test:**
```kotlin
@Test
fun `TLS handshake with multi-record data frames completes successfully`() {
    // Create TLS records that naturally split across WebSocket frames
    // Verify decoder reassembles and handshake completes
    // This tests the actual SSLEngine behavior, not manual mux codec
}
```

**Priority:** HIGH (actual production bug: "TLS handshake failure")

#### 2. Rapid Disconnect/Reconnect Cycles

**File:** `core/tunnel/src/jvmTest/kotlin/com/rousecontext/tunnel/TunnelClientImplTest.kt`

**Test:**
```kotlin
@Test
fun `rapid disconnect and reconnect cycles maintain valid state`() = runBlocking {
    val client = TunnelClientImpl(this, KtorWebSocketFactory())
    client.connect(url)
    
    repeat(5) {
        // Trigger disconnect
        client.disconnect()
        delay(100)
        // Reconnect immediately
        client.connect(url)
        delay(100)
    }
    
    // State should be consistent, no exceptions
    assertEquals(TunnelState.CONNECTED, client.state.value)
}
```

**Priority:** HIGH (actual production bug: spurious disconnect/reconnect)

#### 3. Idempotent Disconnection

**File:** `core/tunnel/src/jvmTest/kotlin/com/rousecontext/tunnel/ConnectionStateMachineTest.kt`

**Test:**
```kotlin
@Test
fun `disconnect from DISCONNECTED state is idempotent`() {
    val machine = ConnectionStateMachine()
    machine.markDisconnected()
    // Should not throw
    machine.markDisconnected()
    assertEquals(TunnelState.DISCONNECTED, machine.state)
}
```

**Priority:** HIGH (actual production bug: `IllegalStateException: Invalid transition from DISCONNECTED to DISCONNECTED`)

#### 4. FCM Token Send After Reconnect

**File:** `core/tunnel/src/jvmTest/kotlin/com/rousecontext/tunnel/TunnelClientImplTest.kt`

**Test:**
```kotlin
@Test
fun `sendFcmToken completes successfully after disconnect/reconnect`() = runBlocking {
    val client = TunnelClientImpl(this, KtorWebSocketFactory())
    val tokenSentJob = launch { client.sendFcmToken("token1") }
    
    // Trigger disconnect while token send is in flight
    delay(50)
    client.disconnect()
    
    // Should not throw JobCancellationException
    // Reconnect and try again
    client.connect(newUrl)
    client.sendFcmToken("token2")
    
    // Both should complete without error
}
```

**Priority:** HIGH (actual production bug: "Failed to send FCM token")

#### 5. Connection Status Observable in ViewModel

**File:** `app/src/test/java/com/rousecontext/app/ui/viewmodels/MainDashboardViewModelTest.kt`

**Test:**
```kotlin
@Test
fun `dashboard updates connection status when tunnel connects`() = runTest {
    // Use real (not mocked) tunnel connection flow
    val vm = MainDashboardViewModel(/* real implementations */)
    
    vm.state.test {
        val initial = awaitItem()
        assertEquals(ConnectionStatus.DISCONNECTED, initial.connectionStatus)
        
        // Trigger tunnel connection
        triggerTunnelConnect()
        
        val updated = awaitItem()
        assertEquals(ConnectionStatus.CONNECTED, updated.connectionStatus)
    }
}
```

**Priority:** HIGH (actual production bug: connection status not updating)

#### 6. Notification Icon Verification

> **Since implemented**, as
> `notifications/src/test/java/com/rousecontext/notifications/NotificationIconTest.kt`.
> The sketch below targets a `NotificationAdapterTest` against a `NotificationAdapter`.
> Both existed when it was written and were deleted as dead code on 2026-04-14 by
> `7a54bdce` (#125), so the sketch cannot be applied as drafted. The shipped test asserts
> the small icon is `ic_stat_rouse` at each notifier rather than probing the resource id.

**File as proposed (since deleted):** `notifications/src/test/java/com/rousecontext/notifications/NotificationAdapterTest.kt`

**Test:**
```kotlin
@Test
fun `posted notification uses valid small icon resource`() {
    adapter.execute(NotificationAction.PostAlert("Alert"))
    
    val shadowManager = Shadows.shadowOf(manager)
    val notification = shadowManager.allNotifications.firstOrNull()
    assertNotNull(notification)
    
    val smallIcon = notification.smallIcon
    assertTrue(smallIcon > 0, "Small icon resource ID must be positive")
    // Verify resource exists in compiled resources
    assertTrue(resourceExists(smallIcon), "Icon resource should exist")
}
```

**Priority:** HIGH (actual production bug: notification icon not rendering)

#### 7. CSP Headers on OAuth Page

**File:** `core/mcp/src/jvmTest/kotlin/com/rousecontext/mcp/core/HttpRoutingTest.kt`

**Test:**
```kotlin
@Test
fun `authorization endpoint returns CSP header that allows inline styles`() = testApplication {
    val response = client.get("/health/authorize")
    
    val csp = response.headers["Content-Security-Policy"]
    assertNotNull(csp)
    // Verify 'unsafe-inline' for style-src or use hash/nonce
    assertTrue(csp.contains("style-src") && 
        (csp.contains("'unsafe-inline'") || csp.contains("'nonce-")))
}
```

**Priority:** HIGH (actual production bug: styles blocked on auth page)

#### 8. Single App Bar on Detail Screens

**File:** `app/src/test/java/com/rousecontext/app/ui/CompositionTest.kt` (NEW)

**Test:**
```kotlin
@Test
fun `audit detail screen has single app bar`() {
    composeRule.setContent {
        RouseContextTheme {
            AuditDetailScreen()
        }
    }
    
    composeRule.onAllNodes(isInstance(TopAppBar::class))
        .assertCountEquals(1)
}
```

**Priority:** HIGH (actual production bug: double app bars)

### MEDIUM PRIORITY (Likely to Cause Bugs)

#### 9. Response Format Without Explicit Nulls

**File:** `core/mcp/src/jvmTest/kotlin/com/rousecontext/mcp/core/McpProtocolTest.kt`

**Test:**
```kotlin
@Test
fun `MCP response contains no explicit null values`() = testApplication {
    // Call tool that returns optional fields
    val response = client.mcpPost(token, callRequest)
    
    val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
    val result = json["result"]?.jsonObject
    
    // Recursively verify no `"field": null`
    assertNoExplicitNulls(result)
}

fun assertNoExplicitNulls(element: JsonElement) {
    if (element is JsonObject) {
        element.forEach { (_, value) ->
            assertTrue("Field should not be explicit null", 
                value !is JsonNull)
            assertNoExplicitNulls(value)
        }
    }
}
```

**Priority:** MEDIUM (may break Claude integration)

#### 10. OAuth Endpoint with Real Hostname

**File:** `core/mcp/src/jvmTest/kotlin/com/rousecontext/mcp/core/HttpRoutingTest.kt`

**Test:**
```kotlin
@Test
fun `oauth metadata uses device subdomain from configuration`() = testApplication {
    val registry = testRegistry("test" to stubProvider("test", "Test"))
    val hostname = "brave-falcon.rousecontext.com"
    
    application {
        configureMcpRouting(
            registry = registry,
            hostname = hostname
        )
    }
    
    val response = client.get("/test/.well-known/oauth-authorization-server")
    val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
    
    assertEquals(
        "https://brave-falcon.rousecontext.com/test",
        json["issuer"]?.jsonPrimitive?.content
    )
}
```

**Priority:** MEDIUM (ensures subdomain correctness)

#### 11. FCM Wake Throttle with Fake Clock

**File:** `work/src/test/kotlin/com/rousecontext/work/FcmDispatchTest.kt`

**Test:**
```kotlin
@Test
fun `wake throttle respects configured timeout`() {
    val clock = FakeClock()
    val throttle = WakeThrottle(timeoutSecs = 300, clock = clock)
    
    assertTrue(throttle.canWake())  // First wake allowed
    assertFalse(throttle.canWake()) // Immediate second wake blocked
    
    clock.advanceSeconds(299)
    assertFalse(throttle.canWake()) // Still blocked
    
    clock.advanceSeconds(1)
    assertTrue(throttle.canWake())  // Now allowed
}
```

**Priority:** MEDIUM (prevents aggressive wake throttle bugs)

#### 12. Concurrent MCP Tool Calls

**File:** `core/mcp/src/jvmTest/kotlin/com/rousecontext/mcp/core/McpSessionTest.kt`

**Test:**
```kotlin
@Test
fun `multiple concurrent tool calls execute independently`() = testApplication {
    val registry = InMemoryProviderRegistry()
    registry.register("test", TestProvider())
    
    val token = tokenStore.createTokenPair("test", "client").accessToken
    
    application { configureMcpRouting(registry, tokenStore) }
    
    // Launch 5 concurrent tool calls
    val jobs = (1..5).map {
        launch {
            val response = client.mcpPost(
                token,
                mcpJsonRpc("tools/call",
                    """{"name":"slow_tool","arguments":{}}""",
                    id = it
                )
            )
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }
    
    jobs.forEach { it.join() }  // All should complete
}
```

**Priority:** MEDIUM (concurrent behavior critical for production)

#### 13. Design System Colors on All Screens

**File:** `app/src/test/java/com/rousecontext/app/ui/DesignSystemTest.kt` (NEW)

**Test:**
```kotlin
@Test
fun `all screens use material design 3 colors from theme`() {
    listOf(
        { DashboardScreen() },
        { AuditDetailScreen() },
        { SettingsScreen() }
        // ... all screens
    ).forEach { screen ->
        composeRule.setContent {
            RouseContextTheme {
                screen()
            }
        }
        
        // Verify no hardcoded colors (e.g., Color(0xFF000000))
        // Use composition tracing or decompile assertions
    }
}
```

**Priority:** MEDIUM (design system consistency)

### LOW PRIORITY (Defense in Depth)

#### 14. Malformed JSON-RPC Requests

**File:** `core/mcp/src/jvmTest/kotlin/com/rousecontext/mcp/core/ErrorResponseTest.kt`

**Tests:**
- Missing `id` field
- Invalid `method` name
- Truncated JSON
- Extra fields
- Wrong type for `params`

#### 15. Corrupted Mux Frames

**File:** `relay/tests/mux_frame_test.rs`

**Tests:**
- Truncated frame header
- Invalid frame type byte
- Payload size mismatch
- Stream ID = 0 (invalid)

#### 16. Token Expiry Race Conditions

**File:** `core/mcp/src/jvmTest/kotlin/com/rousecontext/mcp/core/TokenStoreTest.kt`

**Tests:**
- Concurrent refresh and usage
- Refresh failure during tool call
- Token revocation mid-request

#### 17. Accessibility on All Screens

**File:** `app/src/test/java/com/rousecontext/app/ui/AccessibilityTest.kt` (NEW)

**Tests:**
- All interactive elements have content descriptions
- Text contrast > 4.5:1 (WCAG AA)
- Touch targets >= 48dp

---

## Implementation Roadmap (2026-04-06 snapshot)

### Phase 1: Critical Production Bug Prevention (Week 1)

1. Multi-record TLS frame test
2. Idempotent disconnection test
3. Connection status observable test
4. Notification icon test
5. Rapid reconnect cycle test

**Expected Impact:** Fix 6 of the 12 reported bugs

### Phase 2: Prevent Common Edge Cases (Week 2)

6. FCM token send after reconnect
7. CSP header test for OAuth page
8. Response format (no explicit nulls) test
9. FCM wake throttle test
10. Double app bar test

**Expected Impact:** Catch ~5 new edge cases before production

### Phase 3: Quality Improvements (Week 3+)

11. Concurrent tool calls test
12. Design system compliance test
13. Browser automation for OAuth page
14. Malformed request/frame negative tests
15. Accessibility audit

**Expected Impact:** Long-term stability and maintainability

---

## Test Execution Notes

### Running Tests by Module

```bash
# Tunnel tests
./gradlew :core:tunnel:jvmTest

# MCP tests
./gradlew :core:mcp:jvmTest

# App UI tests (distribution-agnostic; add -Pgoogle for the Firebase build)
./gradlew :app:testDebugUnitTest --tests "*.MainDashboardViewModelTest"

# Screenshot tests (requires graphics)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  ./gradlew :app:testDebugUnitTest --tests "*.ScreenScreenshotTest"

# Relay tests
cd relay && cargo test

# All tests
./gradlew test
cargo test --manifest-path relay/Cargo.toml
```

### Identifying Untested Code Paths

Use coverage tools to find gaps:

```bash
# JVM coverage
./gradlew test jacocoTestReport

# Rust coverage
cd relay && cargo tarpaulin --out Html
```

---

## Summary Table: the 2026-04-06 bug categories, re-checked

Each row states what covers the category **now**, verified against `61c84b93`. The
"April 2026" column is what the original audit said, kept so the delta is visible.

| Bug Category | # Bugs | April 2026 | Covering test at `61c84b93` |
|---|---|---|---|
| TLS/Transport | 3 | Partial — manual frame pumping, no multi-record tests | `TlsAcceptorSplitRecordTest` (record split across DATA frames, both directions); `EndToEndSessionTest` (real relay binary, real handshake). **Still open:** no relay-side frame-boundary test. |
| State Machine | 2 | Partial — no idempotency tests | `ConnectionStateMachineTest` — `transitionToSameStateReturnsFalse`, `concurrentTransitionsAreAtomic` |
| Connection State | 1 | No — ViewModel uses mocks | `DashboardStateFlowTest` |
| Notifications | 1 | Partial — no icon resource verification | `NotificationIconTest` (five posting sites). **Still open:** nothing verifies rendering on a real device. |
| OAuth/Auth | 1 | Partial — no CSP/browser tests | `AuthPageCspTest` (CSP, `X-Frame-Options`, HSTS). **Still open:** no browser-rendered test. |
| Design System | 1 | No — screenshots don't assert | **Still open.** The screenshot tests capture; none asserts on colours, typography, or spacing. |
| UI Layout | 1 | No — no app bar count test | **Still open.** Nothing counts `TopAppBar` instances per screen. |
| FCM/Work | 2 | Weak — no throttle or reconnect tests | `WakeReconnectDeciderTest`, `FcmTokenRegistrarTest`, `RapidFcmWakesTest`, `HalfOpenReconnectTest` |

**Where that leaves it:** of the eight categories, five are now pinned by a named test, two
(design system, UI layout) are untouched, and one (TLS/transport) is covered on the client
and open on the relay. This table is a per-category pointer, not a coverage percentage —
the original "~40% detectable / ~60% require new tests" figure was never derived from a
measurement and has been dropped rather than restated.

---

## References

- Test file locations: see [Test Inventory by Module](#test-inventory-by-module), which is
  the current half of this document
- `docs/audit.md` — the standing recommendation for this file's fate
- Issue [#654](https://github.com/Monkopedia/rouse-context/issues/654) — why the inventory
  grew a **Subject ships?** column
- Ktor testing guide: https://ktor.io/docs/testing.html
- Rust testing guide: https://doc.rust-lang.org/book/ch11-00-testing.html
- Material Design 3 for Compose: https://developer.android.com/design/material3/m3-foundation
