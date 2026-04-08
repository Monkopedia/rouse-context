# Rouse Context Test Coverage Audit

**Date:** 2026-04-06  
**Scope:** Android app + Rust relay test suite  
**Goal:** Identify why production bugs slip through despite hundreds of tests

## Executive Summary

The Rouse Context project has **~70 test files** covering unit, integration, and end-to-end scenarios across Android and Rust components. However, the test suite has critical gaps in three areas:

1. **Integration test fidelity** — Tests use heavy mocks or manual frame pumping, missing real code paths
2. **End-to-end UI and configuration validation** — No tests for design system compliance, hardcoded URLs, notification icons, or status bar colors
3. **Negative and edge-case scenarios** — Missing tests for multi-record TLS frames, expired tokens, partial data, rapid reconnects, and FCM throttle behavior

**Result:** 12+ production bugs have leaked through despite extensive testing (connection status not updating, spurious WebSocket disconnects, TLS handshake failures on closed streams, FCM token send failures, invalid state transitions, etc.)

---

## Test Inventory by Module

### 1. Core Tunnel (JVM tests: `core/tunnel/src/jvmTest/`)

**Files:** 10 test classes, ~3100 lines

| Test Class | Coverage | Status |
|---|---|---|
| `TunnelClientImplTest` | State transitions, FCM token, session handling | ✓ Good |
| `TlsAcceptTest` | TLS handshake over MuxStream | ✓ Good |
| `WebSocketMuxTest` | WebSocket -> mux frame conversion | ✓ Good |
| `MuxFrameTest` (common) | Frame encoding/decoding | ✓ Good |
| `MuxDemuxTest` (common) | Demux logic | ✓ Good |
| `TunnelConnectionStateMachineTest` | State machine transitions | ✓ Good |
| `OnboardingFlowTest` | Full onboarding workflow | ✓ Partial |
| `EndToEndSessionTest` | Real relay binary + TLS | ✓ Excellent |
| `OAuthEndToEndTest` | OAuth flow through tunnel | ✓ Good |
| `RealRelayIntegrationTest` | Raw WebSocket integration | ✓ Good |

**Gaps:**
- No test for **multi-record TLS data frames** (root cause of TLS handshake bugs)
- No test for **rapid disconnect/reconnect cycles** (actual bug: spurious disconnects seen)
- No test for **idempotent disconnection** (actual bug: `IllegalStateException: Invalid transition from DISCONNECTED to DISCONNECTED`)
- No test for **FCM token send after reconnect** (actual bug: JobCancellationException after reconnect)
- No test for **state machine idempotency** or **concurrent state changes**
- TunnelClientImplTest uses manual `MuxCodec` frame pumping, not real TLS handshake variants

### 2. Core MCP (JVM tests: `core/mcp/src/jvmTest/`)

**Files:** 10 test classes

| Test Class | Coverage | Status |
|---|---|---|
| `McpProtocolTest` | tools/call, resources/list, concurrent requests, audit logging | ✓ Excellent |
| `HttpRoutingTest` | OAuth metadata, path routing | ✓ Good |
| `McpSessionTest` | HTTP POST, token auth, tool execution | ✓ Good |
| `AuthorizationCodeFlowTest` | Authorization code PKCE flow | ✓ Good |
| `AuthPageGalleryTest` | Generates static HTML variants | ⚠ Limited |
| `DeviceCodeFlowTest` | Device code flow | ✓ Good |
| `TokenStoreTest` | Token storage and expiry | ✓ Good |
| `AuthMiddlewareTest` | Bearer token validation | ✓ Good |
| `RateLimiterTest` | Rate limit enforcement | ✓ Good |
| `ErrorResponseTest` | Error handling | ✓ Good |

**Gaps:**
- **No test for explicit nulls in JSON responses** — HttpTransport serializes with `explicitNulls = false` but no test verifies Claude can parse responses correctly
- **No test for CSP headers on auth page** — AuthPageGalleryTest generates static HTML but doesn't verify CSP prevents inline styles from being blocked
- **No browser-like test for auth page** — Screenshots don't verify the page renders with styles in actual browser
- **No test for malformed MCP requests** — All tests use valid JSON-RPC
- **No test for token expiry edge cases** — Token revocation, concurrent refresh, race conditions
- **No test for subdomain validation** — URLs hardcoded to test domains, not production subdomains
- **No test for missing OAuth endpoints** — What happens if metadata endpoint is unreachable?

### 3. Core Bridge (JVM tests: `core/bridge/src/jvmTest/`)

**Files:** 2 test classes

| Test Class | Coverage | Status |
|---|---|---|
| `SessionHandlerTest` | Session routing and cleanup | ✓ Good |
| `TunnelSessionManagerTest` | Tunnel state management | ✓ Good |

**Gaps:**
- No tests for **concurrent session operations**
- No tests for **cleanup on unexpected disconnects**
- No tests for **edge cases in session lifecycle**

### 4. App (Unit tests: `app/src/test/`)

**Files:** 5 test classes

| Test Class | Coverage | Status |
|---|---|---|
| `MainDashboardViewModelTest` | State flow, integration visibility, audit entries | ✓ Good |
| `SettingsViewModelTest` | Settings state | ✓ Good |
| `AuditHistoryViewModelTest` | Audit list and filtering | ✓ Good |
| `AuthorizationApprovalViewModelTest` | Auth approval UI state | ✓ Good |
| `AuthApprovalReceiverTest` | BroadcastReceiver for approval | ✓ Good |
| `ScreenScreenshotTest` | Screenshot generation for all screens | ⚠ Limited |

**Gaps (HIGH PRIORITY):**
- **No test for connection status indicator** — Screenshots show "Disconnected" even when connected (actual bug: not tested at all)
- **No test for design system application** — No tests verify colors, typography, spacing follow Material Design 3
- **No test for status bar icon color** — Actual bug: icon colors incorrect (never tested)
- **No test for notification icon color** — Actual bug: icon doesn't render correctly (not tested)
- **No test for double app bar on detail screens** — Screenshots show structure but no assertion tests for it
- **No test for navigation state** — What happens on back press? Navigation cycles?
- **No test for screen composition in dark mode** — ScreenScreenshotTest captures but doesn't assert
- **No test for hardcoded URLs in settings or onboarding** — Integration URLs could be wrong (not tested)

### 5. Notifications (Unit tests: `notifications/src/test/`)

**Files:** 7 test classes

| Test Class | Coverage | Status |
|---|---|---|
| `NotificationChannelsTest` | Channel creation and importance | ✓ Good |
| `NotificationAdapterTest` | Action -> notification posting | ✓ Good |
| `AuthRequestNotifierTest` | Auth request notification | ✓ Good |
| `NotificationModelTest` | Notification data models | ✓ Good |
| `AuditDaoTest` | Audit database persistence | ✓ Good |
| `NotificationDaoTest` | Capture database persistence | ✓ Good |
| `NotificationScreenshotTest` | Screenshot previews | ⚠ Limited |

**Gaps (HIGH PRIORITY):**
- **No test for small icon resource ID** — Actual bug: icon not rendering correctly (never verified)
- **No test for color field** — Notification color field may not match theme
- **No test for icon rendering on actual device** — Screenshot tests don't verify on real Android
- **No test for notification compatibility on different Android versions**
- **No test for accessibility attributes** (content descriptions, etc.)

### 6. Work (Unit tests: `work/src/test/`)

**Files:** 5 test classes

| Test Class | Coverage | Status |
|---|---|---|
| `FcmDispatchTest` | FCM message routing | ✓ Good |
| `CertRenewalWorkerTest` | Cert renewal task | ✓ Good |
| `SecurityCheckWorkerTest` | Security checks | ✓ Good |
| `IdleTimeoutTest` | Idle timeout behavior | ⚠ Partial |
| `WakelockManagerTest` | Wakelock acquisition/release | ⚠ Partial |

**Gaps (HIGH PRIORITY):**
- **No test for FCM wake throttle** — Actual bug: wake throttle too aggressive (never tested with real timing)
- **No test for concurrent FCM messages** — What if multiple wakes arrive in rapid succession?
- **No test for FCM token failure handling** — Actual bug: `Failed to send FCM token to relay` after reconnect (not tested)
- **No test for actual work scheduling** — Tests don't verify WorkManager enqueue behavior

### 7. Relay (Rust tests: `relay/tests/`)

**Files:** 13 test files

| Test File | Coverage | Status |
|---|---|---|
| `integration_test.rs` | Full WebSocket connection, mux frames | ✓ Good |
| `passthrough_test.rs` | Stream passthrough logic | ✓ Good |
| `mux_lifecycle_test.rs` | Stream open/close lifecycle | ✓ Good |
| `mux_frame_test.rs` | Frame encoding/decoding | ✓ Good |
| `config_test.rs` | Config file parsing and env overrides | ✓ Good |
| `api_register_test.rs` | Client registration endpoint | ✓ Good |
| `api_wake_test.rs` | Wake endpoint behavior | ⚠ Limited |
| `api_status_test.rs` | Status endpoint | ✓ Good |
| `api_renew_test.rs` | Cert renewal endpoint | ⚠ Limited |
| `sni_test.rs` | SNI extraction from client certs | ✓ Good |
| `subdomain_test.rs` | Subdomain validation | ✓ Good |
| `maintenance_test.rs` | Device maintenance loop | ⚠ Limited |
| `shutdown_test.rs` | Clean shutdown | ✓ Good |

**Gaps:**
- **No test for multi-record TLS frames** — Actual bug: TLS handshake fails on multi-record data (never tested)
- **No test for WebSocket frame boundaries** — What if TLS record spans multiple WebSocket frames?
- **No test for corrupted or incomplete frames** — Negative test coverage is weak
- **No test for stream cleanup on error** — Error frames don't properly close streams (not verified)
- **No test for concurrent stream limits** — max_streams_per_device not enforced in tests
- **No test for FCM wake race conditions** — What if device disconnects while wake is in flight?

### 8. Health Connect (Unit tests: `health/src/test/`)

**Files:** 2 test classes

| Test Class | Coverage | Status |
|---|---|---|
| `HealthConnectMcpServerTest` | Tool registration and basic calls | ✓ Good |
| `RecordTypeRegistryTest` | Health record type mapping | ✓ Good |

**Gaps:**
- No tests for **permission denial scenarios**
- No tests for **missing data handling**

### 9. Outreach, Usage (Unit tests)

**Files:** 2 test classes, basic coverage

**Gaps:**
- Minimal test coverage
- No integration tests

### 10. API, Device Tests

**Files:** 6 test classes

| Test Class | Coverage | Status |
|---|---|---|
| `IntegrationStateStoreTest` | State persistence | ✓ Good |
| `IntegrationStateTest` | State model | ✓ Good |
| `DeviceIntegrationTest` | End-to-end device flow | ✓ Good |
| `TunnelRelayIntegrationTest` | Tunnel + relay integration | ⚠ Limited |

---

## Gap Analysis: Production Bugs vs. Test Coverage

### Bug 1: TLS Handshake Fails on Multi-Record Data Frames

**Symptom:** "TLS handshake failure" after successful WebSocket connection  
**Root Cause:** TLS record may span multiple WebSocket frames; decoder doesn't handle this  
**Should be tested by:** 
- ✗ No test in `TlsAcceptTest` for multi-record frames
- ✗ No test in relay `mux_frame_test.rs` for frame boundary conditions
- ✗ EndToEndSessionTest uses real handshake but doesn't deliberately send split frames

**Test Location:** `core/tunnel/src/jvmTest/kotlin/com/rousecontext/tunnel/TlsAcceptTest.kt` (add test)  
**Test Location:** `relay/tests/mux_frame_test.rs` (add test)

### Bug 2: OAuth Auth Page Renders with Broken Styles

**Symptom:** Styles not applied on auth page (CSP blocking inline <style> in some clients)  
**Root Cause:** AuthPageGalleryTest generates static HTML but doesn't verify CSP headers or test in browser  
**Should be tested by:**
- ✗ No test verifies CSP headers on auth page response
- ✗ AuthPageGalleryTest generates HTML but doesn't verify it renders with styles
- ✗ No browser automation test (Puppeteer, Playwright, Selenium)

**Test Location:** `core/mcp/src/jvmTest/kotlin/com/rousecontext/mcp/core/AuthPageGalleryTest.kt` (expand)

### Bug 3: Notification Icons Not Rendering Correctly

**Symptom:** Notification icon shows as blank or wrong color  
**Root Cause:** Small icon resource ID incorrect or missing; color field not set  
**Should be tested by:**
- ✗ NotificationAdapterTest doesn't verify small icon ID
- ✗ No test verifies icon is a valid drawable resource
- ✗ No test on actual device (Robolectric shadows don't fully simulate system rendering)

**Test Location:** `notifications/src/test/java/com/rousecontext/notifications/NotificationAdapterTest.kt` (add test)

### Bug 4: Hardcoded URLs Not Caught

**Symptom:** Integration URLs point to wrong subdomain or staging endpoint  
**Root Cause:** No test verifies the hostname parameter flows through to OAuth metadata and MCP endpoints  
**Should be tested by:**
- ✗ McpProtocolTest uses `test.rousecontext.com` but doesn't verify subdomain from device cert
- ✗ HttpRoutingTest doesn't verify `hostname` parameter is used in all OAuth endpoints
- ✗ No test for production hostname overrides

**Test Location:** `core/mcp/src/jvmTest/kotlin/com/rousecontext/mcp/core/HttpRoutingTest.kt` (expand)  
**Test Location:** `core/mcp/src/jvmTest/kotlin/com/rousecontext/mcp/core/McpSessionTest.kt` (add test)

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

### Bug 7: FCM Wake Throttle Too Aggressive

**Symptom:** Device won't wake again within ~5 minutes of last wake  
**Root Cause:** Throttle logic doesn't respect actual elapsed time; may use wall clock instead of timers  
**Should be tested by:**
- ✗ FcmDispatchTest doesn't test throttle behavior at all
- ✗ No test with fake clock to verify throttle timing
- ✗ No test for rapid successive wake broadcasts

**Test Location:** `work/src/test/kotlin/com/rousecontext/work/FcmDispatchTest.kt` (expand)

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

### Bug 11: Spurious Disconnect/Reconnect Cycles

**Symptom:** WebSocket disconnects and reconnects repeatedly within seconds of connection  
**Root Cause:** Connection state machine or WebSocket error handling not properly idempotent  
**Should be tested by:**
- ✗ TunnelClientImplTest doesn't test rapid disconnect/reconnect
- ✗ No test for concurrent state transitions
- ✗ No test for state race conditions

**Test Location:** `core/tunnel/src/jvmTest/kotlin/com/rousecontext/tunnel/TunnelClientImplTest.kt` (add test)

### Bug 12: FCM Token Send Fails After Reconnect

**Symptom:** "Failed to send FCM token to relay" with JobCancellationException  
**Root Cause:** Coroutine for sending token is cancelled before completion on reconnect  
**Should be tested by:**
- ✗ TunnelClientImplTest tests sendFcmToken but not after disconnect/reconnect cycle
- ✗ No test for concurrent cancellation safety
- ✗ No test for token send on reconnect path

**Test Location:** `core/tunnel/src/jvmTest/kotlin/com/rousecontext/tunnel/TunnelClientImplTest.kt` (add test)

### Bug 13: Invalid State Transition (DISCONNECTED -> DISCONNECTED)

**Symptom:** `IllegalStateException: Invalid transition from DISCONNECTED to DISCONNECTED`  
**Root Cause:** State machine doesn't allow idempotent disconnection  
**Should be tested by:**
- ✗ TunnelClientImplTest doesn't test idempotent disconnect
- ✗ ConnectionStateMachineTest may not cover this case

**Test Location:** `core/tunnel/src/jvmTest/kotlin/com/rousecontext/tunnel/ConnectionStateMachineTest.kt` (verify or add)

---

## Missing Test Categories

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

## Test Quality Issues

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

## Recommended New Tests

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

**File:** `notifications/src/test/java/com/rousecontext/notifications/NotificationAdapterTest.kt`

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

## Implementation Roadmap

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

# App UI tests
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

## Summary Table: Test Coverage by Bug Category

| Bug Category | # Bugs | Tests Exist | Root Cause | Recommendation |
|---|---|---|---|---|
| TLS/Transport | 3 | Partial | Manual frame pumping, no multi-record tests | Add real handshake tests |
| State Machine | 2 | Partial | No idempotency tests | Add idempotent disconnect test |
| Connection State | 1 | No | ViewModel uses mocks | Add integration test with real stores |
| Notifications | 1 | Partial | No icon resource verification | Add resource validation test |
| OAuth/Auth | 1 | Partial | No CSP/browser tests | Add browser automation test |
| Design System | 1 | No | Screenshots don't assert | Add composition assertions |
| UI Layout | 1 | No | No app bar count test | Add Compose hierarchy test |
| FCM/Work | 2 | Weak | No throttle or reconnect tests | Add timing and concurrency tests |

**Overall:** 12 bugs, ~40% detectable by existing tests with minor fixes, ~60% require new tests

---

## References

- Production bug report: `docs/device-test-report.md`
- Test file locations: See inventory section above
- Ktor testing guide: https://ktor.io/docs/testing.html
- Rust testing guide: https://doc.rust-lang.org/book/ch11-00-testing.html
- Material Design 3 for Compose: https://developer.android.com/design/material3/m3-foundation
