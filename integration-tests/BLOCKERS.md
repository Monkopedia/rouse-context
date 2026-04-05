# Integration Test Blockers

## Status: Partial Implementation

The integration tests use a mock relay server (Ktor WebSocket) that speaks the mux
framing protocol. All 5 test scenarios from overall.md (IDs 100-104) are covered
against the mock relay.

## Blockers for Real Relay Integration

### 1. Relay binary does not serve yet

`relay/src/main.rs` has a TODO: "bind TCP listener, accept connections, route by SNI".
The binary starts, logs, and exits immediately. There is no TCP listener, no WebSocket
upgrade, no mTLS handshake.

**Impact**: Cannot start the relay as a subprocess and connect to it from Kotlin.

**Resolution**: Complete the relay's main loop (bind TCP, TLS accept, HTTP routing,
WebSocket upgrade). Once the relay serves on a port, the integration test can switch
from MockRelayServer to a real subprocess.

### 2. /ws endpoint is a stub

`relay/src/api/ws.rs` always returns 401 ("Valid client certificate required").
It does not perform WebSocket upgrade or run the mux session lifecycle.

**Impact**: Even if the relay served, WebSocket connections would be rejected.

**Resolution**: Implement the WebSocket upgrade handler that:
- Extracts the subdomain from the client certificate
- Upgrades to WebSocket
- Creates a MuxSession and runs the read/write loops

### 3. No mTLS support in relay main loop

The relay's TLS config (`relay/src/tls.rs`) uses `with_no_client_auth()`. For real
integration tests, the relay needs to require and validate client certificates.

**Impact**: Cannot test mTLS handshake rejection (test 103: expired cert).

**Resolution**: Add a client CA certificate to the relay config and configure
`with_client_cert_verifier()` using it.

### 4. Tunnel module is Android-only

The tunnel module (`tunnel/`) uses the Android library Gradle plugin. It cannot be
depended on from a pure JVM module. The design doc calls for KMP with `jvmTest`
but the module has not been converted.

**Impact**: Integration tests cannot import tunnel classes directly. The mux frame
implementation is duplicated between `tunnel/` and `integration-tests/`.

**Resolution**: Convert the tunnel module to Kotlin Multiplatform with `commonMain`
for protocol logic (Frame, TunnelClient interface) and `androidMain` for Android
specifics (FCM, Keystore). Then `jvmTest` can test protocol logic directly.

## What Works Today

- Mux frame encode/decode (Kotlin): tested in tunnel module unit tests (12 tests)
- Wire format compatibility: manually verified against Rust relay's frame.rs
- Mock relay WebSocket integration: 7 tests covering all 5 test scenarios
  - Test 100: Connection established
  - Test 101: OPEN/DATA/CLOSE round-trip with data integrity
  - Test 102: Multiple concurrent independent streams
  - Test 103: ERROR frame parsing (STREAM_REFUSED with message)
  - Test 104: Graceful shutdown (CLOSE for all streams)
  - Bonus: Device-initiated CLOSE
  - Bonus: Large payload (64KB) integrity
