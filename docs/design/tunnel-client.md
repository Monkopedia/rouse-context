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
- On OPEN frame: create raw stream pair, wrap in TLS server accept (using device cert + private key from `CertificateStore`), emit plaintext stream to app layer
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
`FirebaseMessagingService` subclass in `androidMain`. On receiving a data message:

1. Extract `type`, `relay_host`, `relay_port` from data payload
2. Dispatch by type:
   - `"wake"` (high priority): start TunnelService (foreground service), open mux WebSocket to `relay_host:relay_port`
   - `"renew"` (normal priority): trigger immediate cert renewal via WorkManager (no mux connection needed)
   - Unknown type: log warning, ignore

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
- Check cert expiry date from `CertificateStore`
- If <14 days remaining: call `POST /renew` with mTLS (proactive, silent)
- If cert already expired: call `POST /renew` with Firebase token + signature
- On failure: WorkManager retry with exponential backoff
- On `type: "renew"` FCM (7-day nudge from relay): trigger immediate renewal attempt

### Renewal Over Active Mux
Device just makes an HTTPS call to `POST /renew` using its current cert for mTLS auth. The mux connection is unaffected. After getting the new cert, device stores it. Next mux connection uses the new cert.

### Post-Renewal Validation
After receiving a new cert from the relay, the device MUST verify the cert's CN/SAN matches its stored subdomain before storing. Reject and log an error if mismatched.

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

## CertificateStore Interface

The tunnel needs certs for TLS but doesn't own storage. The app provides an implementation.

```kotlin
interface CertificateStore {
    /** Device cert chain for TLS server accept and mTLS to relay */
    fun getCertChain(): List<X509Certificate>?

    /** Private key reference from Android Keystore */
    fun getPrivateKey(): PrivateKey?

    /** Store a new cert chain (after onboarding or renewal) */
    fun storeCertChain(certs: List<X509Certificate>)

    /** Device subdomain (for mTLS SNI to relay) */
    fun getSubdomain(): String?

    /** Cert expiry for renewal checks */
    fun getCertExpiry(): Instant?
}
```

- Interface defined in tunnel's `commonMain`
- `:app` provides Android implementation (PEM files in `filesDir`, private key from Keystore)
- `jvmTest` uses in-memory implementation

## Security Monitoring

### Self-Cert Verification

The app periodically connects to its own relay subdomain and verifies the TLS leaf cert fingerprint matches the cert it provisioned. Implementation:

1. Custom `X509TrustManager` that extracts the SHA-256 fingerprint of the leaf cert's public key
2. Compares against the fingerprint stored in the Android Keystore alongside the private key
3. During the 90-day renewal window, stores both current and pending renewal fingerprints — accepts either as valid to avoid false positives during legitimate rotation
4. Mismatch triggers an immediate user-facing alert

The `CertificateStore` interface gains two methods:

```kotlin
/** SHA-256 fingerprints of cert public keys we issued (current + pending renewal) */
fun getKnownFingerprints(): Set<String>

/** Store a fingerprint for a newly issued cert */
fun storeFingerprint(fingerprint: String)
```

### Certificate Transparency Monitoring

The app periodically queries CT logs for any cert issued against its subdomain:

1. Query `https://crt.sh/?q={subdomain}.rousecontext.com&output=json`
2. Parse the JSON response — each entry has an `id`, `issuer_name`, `not_before`, and `serial_number`
3. Cross-reference against the cert the app provisioned (match by serial number or public key fingerprint)
4. If CT shows any cert for this subdomain that the app didn't issue, surface an immediate alert

This defeats targeted interception attacks where an adversary filters the self-check traffic but MITMs actual AI client sessions — they can't hide a fraudulent cert from the CT logs.

### Scheduling

Both checks are WorkManager periodic tasks (not blocking user sessions):
- Run on first launch after onboarding
- Then every 4 hours (`PeriodicWorkRequest` with flex window)
- Failed checks degrade to a visible warning state — never silently swallowed
- Results stored in the local audit log alongside tool call history
- If `getCertChain()` returns null → `TunnelError.CertExpired` (or `CertUnavailable`)

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
    val streamId: Int         // u32, mux routing ID (per-connection)
    val sessionId: String     // UUID, generated by tunnel, for audit log + notifications
    val sniHostname: String
    val input: InputStream    // plaintext (post-TLS termination by tunnel)
    val output: OutputStream  // plaintext (tunnel encrypts on the way out)
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
