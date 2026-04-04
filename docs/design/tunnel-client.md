# Tunnel Client Design

## Overview

Kotlin Multiplatform module (`:tunnel`). Core protocol logic in `commonMain` (Ktor WebSocket client, mux framing, session lifecycle). Android-specific code in `androidMain` (FCM, Keystore, WorkManager). JVM test target for integration tests against a test relay.

## Module Structure (KMP)

```
tunnel/
  src/commonMain/     ← mux protocol, framing, WebSocket client, session lifecycle
  src/commonTest/     ← protocol unit tests
  src/androidMain/    ← FCM receiver, Android Keystore, WorkManager cert renewal
  src/androidTest/    ← Android-specific tests
  src/jvmMain/        ← JVM-specific implementations (for test relay, etc.)
  src/jvmTest/        ← full integration tests against test relay
```

## Mux Client

The mux client manages the WebSocket connection to the relay and demultiplexes incoming frames into per-stream I/O pairs.

### Responsibilities
- Open and maintain WebSocket connection to relay (with mTLS using device cert)
- Parse incoming binary frames (5-byte header: type u8, stream_id u32 BE, payload)
- On OPEN frame: create a new local stream pair (InputStream/OutputStream), notify the app layer
- On DATA frame: route payload bytes to the correct stream's InputStream
- On CLOSE frame: close the corresponding stream pair
- On ERROR frame: tear down the affected stream, propagate error
- Outbound: accept bytes from stream OutputStreams, frame as DATA, send over WebSocket
- WebSocket ping interval: ~30s (configurable, for NAT/mobile idle timeout survival)

### Stream Demux
Each stream ID maps to a bidirectional byte channel. On the device side, each stream looks like a plain `InputStream`/`OutputStream` pair — `McpSession` never knows it's multiplexed.

### Connection State Machine
```
DISCONNECTED → CONNECTING → CONNECTED → DISCONNECTING → DISCONNECTED
```

- **DISCONNECTED**: no WebSocket. Waiting for FCM or explicit connect.
- **CONNECTING**: WebSocket handshake + mTLS in progress.
- **CONNECTED**: ready to receive OPEN frames and carry streams.
- **DISCONNECTING**: teardown in progress, sending CLOSE for active streams.

No reconnect during active streams. If WebSocket drops, all streams die. Clean restart on next FCM.

## FCM Wakeup

### Receiver
`FirebaseMessagingService` subclass in `androidMain`. On receiving a high-priority data message:

1. Extract `type` and `relay_host` from data payload
2. If `type == "wake"`: start TunnelService (foreground service)
3. TunnelService opens mux WebSocket to `relay_host`

### FCM Token Refresh
`onNewToken()` callback → write new token directly to Firestore using Firebase SDK.
If mux connection is active, no disruption — token is only used by relay for FCM sends.

## Android Keystore Integration

### Key Generation (onboarding)
```kotlin
val keyPairGenerator = KeyPairGenerator.getInstance("EC", "AndroidKeyStore")
keyPairGenerator.initialize(
    KeyGenParameterSpec.Builder("rouse_device_key", PURPOSE_SIGN)
        .setDigests(KeyProperties.DIGEST_SHA256)
        .build()
)
val keyPair = keyPairGenerator.generateKeyPair()
```

- Algorithm: ECDSA P-256 (widely supported, compact signatures)
- Key never exportable, hardware-backed where available

### CSR Generation
Use Bouncy Castle or a lightweight ASN.1 library to create a PKCS#10 CSR signed by the Keystore private key. The CSR doesn't include the subdomain (relay assigns it) — just the public key.

### Cert Storage
The signed cert returned from the relay is stored in app-private storage (not the Keystore — Keystore holds keys, not certs). Loaded at mux connection time for mTLS.

### Signature for Expired Renewal
```kotlin
val signature = Signature.getInstance("SHA256withECDSA")
signature.initSign(privateKey) // from Keystore
signature.update(csrBytes)
val signed = signature.sign()
```

## Cert Renewal

### WorkManager Job
- Periodic: once daily
- Constraint: network available
- Check cert expiry date from stored cert
- If <30 days remaining and mux connected: call `POST /renew` with mTLS
- If <30 days remaining and not connected: call `POST /renew` with mTLS (open temporary connection)
- If cert already expired: call `POST /renew` with Firebase token + signature
- On failure: WorkManager retry with exponential backoff

### Renewal Over Active Mux
Device just makes an HTTPS call to `POST /renew` using its current cert for mTLS auth. The mux connection is unaffected. After getting the new cert, device stores it. Next mux connection uses the new cert.

## Onboarding Flow (device side)

1. Check if device has stored cert + subdomain → if yes, skip onboarding
2. Generate keypair in Android Keystore
3. Firebase anonymous auth → get UID
4. Get FCM token
5. Generate CSR
6. Call `POST /register` on relay with Firebase token + CSR + FCM token
7. Receive subdomain + signed cert
8. Store cert + subdomain in app-private storage
9. Onboarding complete → UI shows device subdomain

If any step fails, show error in UI with retry option. No partial state — either fully onboarded or not.

## Interface to App Layer

The tunnel module exposes to `:app`:

```kotlin
enum class TunnelState {
    DISCONNECTED,    // no mux connection
    CONNECTING,      // WebSocket handshake in progress
    CONNECTED,       // mux open, no active streams
    ACTIVE,          // mux open, 1+ active streams
    DISCONNECTING,   // teardown in progress
}

sealed interface TunnelError {
    data object ConnectionFailed : TunnelError
    data object AuthRejected : TunnelError       // mTLS handshake failed
    data object CertExpired : TunnelError         // cert needs renewal before connecting
    data class StreamError(val streamId: Int, val code: ErrorCode) : TunnelError
    data class Unexpected(val message: String) : TunnelError
}

interface TunnelClient {
    /** Current connection + stream state */
    val state: StateFlow<TunnelState>

    /** Errors emitted for app to handle (notifications, audit, UI) */
    val errors: SharedFlow<TunnelError>

    /** Connect to relay (called by foreground service on FCM wakeup) */
    suspend fun connect()

    /** Disconnect from relay */
    suspend fun disconnect()

    /** Emitted when relay sends OPEN — app should create McpSession for this stream */
    val incomingSessions: Flow<MuxStream>
}

interface MuxStream {
    val streamId: Int
    val sniHostname: String
    val input: InputStream
    val output: OutputStream
    suspend fun close()
}
```

`:app` collects `incomingSessions`, creates `McpSession(providers)` for each, and calls `session.run(stream.input, stream.output)`.

See `docs/design/relay.md § Mux Framing Protocol` for the wire format (message types, error codes, byte layout).

## Dependencies

### commonMain (KMP)
- `io.ktor:ktor-client-core` — HTTP + WebSocket client
- `io.ktor:ktor-client-websockets` — WebSocket support
- `kotlinx-coroutines-core`
- `kotlinx-io-core` — byte buffer utilities

### androidMain
- `com.google.firebase:firebase-messaging` — FCM
- `androidx.work:work-runtime-ktx` — WorkManager for cert renewal
- `androidx.lifecycle:lifecycle-service` — foreground service base
- Bouncy Castle or similar for CSR generation

### jvmTest
- Ktor client with CIO engine
- Test relay implementation (in-process mock)

## Still Needs Design

(none — all items resolved. CSR: Bouncy Castle. Cert storage: PEM file in app-private dir. Error propagation: TunnelError sealed class via SharedFlow. TunnelState enum with ACTIVE for 1+ streams. Foreground service + Doze + wakelocks: app concerns, see android-app.md. Mux wire format: see relay.md.)
