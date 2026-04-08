---
title: "Per-Integration Hostnames"
date: 2026-04-08
status: proposed
---

## Design Document: Per-Integration Hostnames

### 1. URL Format

**Current:** `https://{secret-prefix}.{subdomain}.rousecontext.com/{integration}/mcp`
- Example: `https://brave-falcon.cool-penguin.rousecontext.com/health/mcp`
- One secret prefix shared across all integrations
- Integration identity encoded in URL path

**New:** `https://{integration-secret}.{subdomain}.rousecontext.com/mcp`
- Example: `https://brave-health.cool-penguin.rousecontext.com/mcp`
- Each integration gets its own secret word (e.g., `brave-health` for health, `falcon-outreach` for outreach)
- The first SNI label encodes both the secret and the integration identity
- Path is always `/mcp` -- no path-based routing
- Wildcard cert `*.{subdomain}.rousecontext.com` already covers this -- no cert changes

---

### 2. Firestore Schema Changes

**File:** `relay/src/firestore.rs`

The `DeviceRecord` struct changes from a single `secret_prefix: Option<String>` to a map of per-integration secrets.

**Current field:**
```rust
pub secret_prefix: Option<String>,
```

**New field:**
```rust
/// Per-integration secrets. Key = integration name (e.g. "health"),
/// value = secret word (e.g. "brave-health").
/// Empty map for legacy devices that haven't migrated yet.
pub integration_secrets: HashMap<String, String>,
```

Remove the `secret_prefix` field entirely. The migration section below covers backward compatibility.

The `FirestoreClient` trait is unchanged -- `get_device` / `put_device` work with `DeviceRecord` as before. The schema change is purely structural within the record.

---

### 3. SNI Parsing Changes

**File:** `relay/src/sni.rs`

The `RouteDecision` enum changes:

**Current:**
```rust
DevicePassthrough {
    subdomain: String,
    secret_prefix: String,
}
```

**New:**
```rust
DevicePassthrough {
    subdomain: String,
    integration_secret: String,  // renamed from secret_prefix
}
```

The `from_sni` method logic is unchanged -- it still splits the first label from the subdomain. The field is renamed to `integration_secret` to reflect that it now carries integration identity, not just a secret prefix. The actual validation (matching the secret to an integration) happens in `passthrough.rs`, not here. SNI parsing remains a pure string split with no Firestore lookup.

---

### 4. Relay Passthrough Changes

**File:** `relay/src/passthrough.rs`

The `resolve_device_stream` function currently validates `secret_prefix` against `device.secret_prefix`. This changes to validate `integration_secret` against the `device.integration_secrets` map and extract the integration name.

**Current validation:**
```rust
if let Some(stored_secret) = &device.secret_prefix {
    if stored_secret != secret_prefix {
        return Err(PassthroughError::InvalidSecret);
    }
}
```

**New validation:**
```rust
// Find which integration this secret corresponds to
let integration = device.integration_secrets.iter()
    .find(|(_k, v)| v.as_str() == integration_secret)
    .map(|(k, _v)| k.clone());

let integration = match integration {
    Some(name) => name,
    None => {
        info!(subdomain, "Integration secret mismatch (silent reject)");
        return Err(PassthroughError::InvalidSecret);
    }
};
```

The `resolve_device_stream` function signature changes to return the resolved integration name alongside the stream. Add an `integration` field to `ResolvedStream`:

```rust
pub struct ResolvedStream {
    pub handle: MuxHandle,
    pub stream_id: u32,
    pub stream_rx: mpsc::Receiver<Frame>,
    pub integration: String,  // NEW: resolved integration name
}
```

**File:** `relay/src/main.rs`

The SNI hostname construction for the OPEN frame changes. Currently:
```rust
let sni_hostname = format!("{secret_prefix}.{subdomain}.{}", ctx.base_domain);
```

This stays the same conceptually (the OPEN frame payload is the full SNI hostname), but the variable is renamed:
```rust
let sni_hostname = format!("{integration_secret}.{subdomain}.{}", ctx.base_domain);
```

No functional change here -- the OPEN frame payload is still the full hostname string. The device uses this as the TLS SNI for its server cert.

---

### 5. Registration API Changes

**File:** `relay/src/api/register.rs`

**Round 1 response** changes from returning a single `secret_prefix` to returning a map of integration secrets.

**Current `RegisterResponse`:**
```rust
pub struct RegisterResponse {
    pub subdomain: String,
    pub secret_prefix: String,
    pub relay_host: String,
}
```

**New `RegisterResponse`:**
```rust
pub struct RegisterResponse {
    pub subdomain: String,
    /// Map of integration name -> secret word.
    /// e.g. {"health": "brave-health", "outreach": "falcon-outreach"}
    pub integration_secrets: HashMap<String, String>,
    pub relay_host: String,
}
```

**Secret generation:** Instead of generating one `secret_prefix`, generate a secret for each known integration. The set of integrations is defined server-side (a config list). For each integration, generate a unique `adj-noun` slug:

```rust
let mut integration_secrets = HashMap::new();
for integration in &state.config.integrations {
    let secret = state.subdomain_generator.generate();
    integration_secrets.insert(integration.clone(), secret);
}
```

Store this map in the `DeviceRecord`:
```rust
let record = DeviceRecord {
    // ... existing fields ...
    integration_secrets,
};
```

The relay config needs a list of known integration names (e.g., `["health", "outreach", "notifications", "usage"]`). This is a new config field.

---

### 6. Secret Rotation API Changes

**File:** `relay/src/api/rotate_secret.rs`

**Current `RotateSecretResponse`:**
```rust
pub struct RotateSecretResponse {
    pub secret_prefix: String,
}
```

**New `RotateSecretResponse`:**
```rust
pub struct RotateSecretResponse {
    /// New map of integration name -> secret word. All secrets rotated at once.
    pub integration_secrets: HashMap<String, String>,
}
```

**Handler logic:** Instead of generating one new secret, regenerate secrets for all integrations the device has:

```rust
let mut new_secrets = HashMap::new();
for integration in record.integration_secrets.keys() {
    new_secrets.insert(integration.clone(), state.subdomain_generator.generate());
}
// Also add any new integrations from config that weren't present before
for integration in &state.config.integrations {
    new_secrets.entry(integration.clone())
        .or_insert_with(|| state.subdomain_generator.generate());
}
record.integration_secrets = new_secrets.clone();
```

---

### 7. Tunnel Client / Relay API Client Changes

**File:** `core/tunnel/src/commonMain/kotlin/com/rousecontext/tunnel/RelayApiClient.kt`

**`RegisterResponse`:**

**Current:**
```kotlin
data class RegisterResponse(
    val subdomain: String,
    val relayHost: String,
    val secretPrefix: String? = null
)
```

**New:**
```kotlin
data class RegisterResponse(
    val subdomain: String,
    val relayHost: String,
    @SerialName("integration_secrets")
    val integrationSecrets: Map<String, String> = emptyMap()
)
```

**`RotateSecretResponse`:**

**Current:**
```kotlin
data class RotateSecretResponse(
    @SerialName("secret_prefix") val secretPrefix: String
)
```

**New:**
```kotlin
data class RotateSecretResponse(
    @SerialName("integration_secrets")
    val integrationSecrets: Map<String, String>
)
```

---

### 8. Certificate Store Changes

**File:** `core/tunnel/src/commonMain/kotlin/com/rousecontext/tunnel/CertificateStore.kt`

Replace the single secret prefix storage with per-integration secret storage.

**Remove:**
```kotlin
suspend fun storeSecretPrefix(prefix: String)
suspend fun getSecretPrefix(): String?
```

**Add:**
```kotlin
/** Store per-integration secrets map (e.g. {"health": "brave-health"}). */
suspend fun storeIntegrationSecrets(secrets: Map<String, String>)

/** Retrieve stored per-integration secrets, or null if not yet assigned. */
suspend fun getIntegrationSecrets(): Map<String, String>?

/** Get the secret for a specific integration, or null. */
suspend fun getSecretForIntegration(integration: String): String? {
    return getIntegrationSecrets()?.get(integration)
}
```

All implementations of `CertificateStore` (Android Keystore-backed, in-memory test) must be updated. The Android implementation serializes this as a JSON string in SharedPreferences or similar.

---

### 9. Onboarding Flow Changes

**File:** `core/tunnel/src/commonMain/kotlin/com/rousecontext/tunnel/OnboardingFlow.kt`

**Current:**
```kotlin
certificateStore.storeSubdomain(registerData.subdomain)
registerData.secretPrefix?.let { certificateStore.storeSecretPrefix(it) }
```

**New:**
```kotlin
certificateStore.storeSubdomain(registerData.subdomain)
if (registerData.integrationSecrets.isNotEmpty()) {
    certificateStore.storeIntegrationSecrets(registerData.integrationSecrets)
}
```

---

### 10. App URL Builder Changes

**File:** `app/src/main/java/com/rousecontext/app/UrlBuilder.kt`

The `buildMcpUrl` function changes from path-based to hostname-based integration identity.

**Current:**
```kotlin
fun buildMcpUrl(
    secretPrefix: String,
    subdomain: String,
    baseDomain: String,
    integrationPath: String
): String {
    val host = "$secretPrefix.$subdomain.$baseDomain"
    return "https://$host$integrationPath/mcp"
}
```

**New:**
```kotlin
fun buildMcpUrl(
    integrationSecret: String,
    subdomain: String,
    baseDomain: String
): String {
    val host = "$integrationSecret.$subdomain.$baseDomain"
    return "https://$host/mcp"
}
```

The `McpUrlProvider` class changes:

**Current `buildUrl`:**
```kotlin
suspend fun buildUrl(integrationPath: String): String? {
    val subdomain = certStore.getSubdomain() ?: return null
    val secretPrefix = certStore.getSecretPrefix() ?: return null
    return buildMcpUrl(secretPrefix, subdomain, baseDomain, integrationPath)
}
```

**New `buildUrl`:**
```kotlin
suspend fun buildUrl(integration: String): String? {
    val subdomain = certStore.getSubdomain() ?: return null
    val secret = certStore.getSecretForIntegration(integration) ?: return null
    return buildMcpUrl(secret, subdomain, baseDomain)
}
```

**Current `buildHostname`:**
```kotlin
suspend fun buildHostname(): String? {
    val subdomain = certStore.getSubdomain() ?: return null
    val secretPrefix = certStore.getSecretPrefix() ?: return null
    return "$secretPrefix.$subdomain.$baseDomain"
}
```

This method no longer makes sense as a single hostname -- each integration has its own hostname. Change to:
```kotlin
suspend fun buildHostname(integration: String): String? {
    val subdomain = certStore.getSubdomain() ?: return null
    val secret = certStore.getSecretForIntegration(integration) ?: return null
    return "$secret.$subdomain.$baseDomain"
}
```

---

### 11. MCP Routing Changes

**File:** `core/mcp/src/commonMain/kotlin/com/rousecontext/mcp/core/McpRouting.kt`

This is the largest change. Currently, all routes are under `route("/{integration}") { ... }`, and the integration name is extracted from the URL path. In the new design, there is only one path (`/mcp` and OAuth endpoints at the root) and the integration is determined by the hostname.

**Approach:** The `configureMcpRouting` function receives the integration name as a parameter (determined before the HTTP server sees the request). Since the device runs one `McpSession` per integration-secret connection, the session already knows which integration it serves.

However, this is a significant architectural shift. Currently one `McpSession` (one embedded HTTP server) handles all integrations via path routing. Two approaches:

**Option A: One McpSession per integration.** Each incoming mux stream carries integration identity (from the OPEN frame payload / SNI hostname). The device parses the integration name from the hostname and creates/reuses an `McpSession` specific to that integration. The routing in `McpRouting.kt` simplifies to root-level routes (`/mcp`, `/token`, `/authorize`, etc.) with no `{integration}` path parameter.

**Option B: Keep one McpSession, pass integration via header.** The existing path-based routing stays, but the TLS layer injects a header with the integration name parsed from SNI. The HTTP request arrives at `/mcp` but gets internally routed to the correct integration.

**Recommendation: Option A** is cleaner and matches the design goal. The `McpSession` hostname already reflects the integration identity. Implementation:

1. The `MuxFrame.Open` on the Kotlin side gains a payload field to carry the hostname:
   - **File:** `core/tunnel/src/commonMain/kotlin/com/rousecontext/tunnel/MuxFrame.kt`
   - Change: `data class Open(override val streamId: UInt, val hostname: String = "") : MuxFrame()`
   - **File:** `core/tunnel/src/commonMain/kotlin/com/rousecontext/tunnel/MuxCodec.kt`
   - Change: decode OPEN payload as UTF-8 string hostname: `MuxFrame.Open(streamId, payload.decodeToString())`

2. `SessionHandler` extracts the integration name from the hostname in the OPEN frame, then routes to the correct `McpSession`.

3. The integration name is parsed from the hostname: given `brave-health.cool-penguin.rousecontext.com`, the first label is `brave-health`. The device looks up which integration that secret maps to via `CertificateStore.getIntegrationSecrets()` (reverse lookup: find the key whose value is `brave-health` -> that key is `health`).

4. `McpRouting.kt` routes are simplified -- the `route("/{integration}")` wrapper is removed. All routes move to root level. The `hostname` parameter to `configureMcpRouting` now determines which single integration this session serves. OAuth metadata endpoints use root paths:
   - `/.well-known/oauth-authorization-server` (no path suffix)
   - `/authorize`, `/device/authorize`, `/token`, `/register`
   - `/mcp`

**File:** `core/mcp/src/commonMain/kotlin/com/rousecontext/mcp/core/OAuthMetadata.kt`

The `buildOAuthMetadata` function simplifies -- `integrationPath` parameter is removed since everything is at root:
```kotlin
fun buildOAuthMetadata(hostname: String): OAuthMetadata {
    val baseUrl = "https://$hostname"
    return OAuthMetadata(
        issuer = baseUrl,
        authorizationEndpoint = "$baseUrl/authorize",
        // ... etc
    )
}
```

---

### 12. MuxStream and SessionHandler Integration Routing

**File:** `core/bridge/src/commonMain/kotlin/com/rousecontext/bridge/SessionHandler.kt`

Currently `handleStream` creates an `McpSession` via `mcpSessionFactory.create()` with no integration context. This must change to accept the integration name:

```kotlin
suspend fun handleStream(stream: MuxStream) {
    // Extract hostname from the OPEN frame payload
    val hostname = stream.hostname  // new field from MuxFrame.Open payload

    // Resolve integration name from hostname
    val integrationSecret = hostname.substringBefore('.')
    val integration = secretToIntegration(integrationSecret)
        ?: throw IllegalStateException("Unknown integration secret: $integrationSecret")

    val mcpHandle = mcpSessionFactory.create(integration, hostname)
    // ... rest unchanged
}
```

The `McpSessionFactory` interface gains an `integration` and `hostname` parameter. The `TunnelSessionManager` passes these through.

---

### 13. Migration Plan

**Phase 1 -- Relay backward compatibility (deploy first):**
- Add `integration_secrets` field to `DeviceRecord` alongside existing `secret_prefix`
- `resolve_device_stream` checks `integration_secrets` first; if empty, falls back to `secret_prefix` with old path-based routing
- Registration returns both `secret_prefix` (old format) and `integration_secrets` (new format)
- SNI validation: if `integration_secrets` has a matching value, use new flow; otherwise, check `secret_prefix` for old flow

**Phase 2 -- Client update (app release):**
- App reads `integration_secrets` from registration response
- Stores per-integration secrets in `CertificateStore`
- Builds new-format URLs
- On first connect after update, existing devices call `rotate-secret` which returns `integration_secrets` (relay generates per-integration secrets from the old single prefix)

**Phase 3 -- Deprecation (after migration window):**
- Remove `secret_prefix` field from `DeviceRecord`
- Remove fallback path-based routing from relay
- Remove `storeSecretPrefix` / `getSecretPrefix` from `CertificateStore`

**Migration for existing devices:** When a device with only `secret_prefix` calls `rotate-secret`, the relay detects the legacy format and generates `integration_secrets` for all configured integrations. The old `secret_prefix` is cleared. This is a one-time migration triggered by the app update.

---

### 14. Security Considerations

- **Same enumeration resistance:** Each integration secret is independently generated from ~4M combinations. Knowing one integration's secret does not reveal others.
- **Per-integration rotation granularity:** Although the current design rotates all at once, the schema supports future per-integration rotation if needed.
- **No timing oracle:** The relay still rejects at the same point regardless of whether the integration exists -- the Firestore lookup happens for any `DevicePassthrough` decision, and the reject path is identical.
- **OPEN frame payload trust:** The device must validate that the hostname in the OPEN frame matches one of its known integration secrets. A compromised relay could send arbitrary hostnames. The device should check against its stored secrets map.

---

### 15. Implementation Sequence

1. **Relay Firestore schema** -- Add `integration_secrets` to `DeviceRecord`, keep `secret_prefix` for backward compat
2. **Relay registration** -- Generate and return `integration_secrets` alongside `secret_prefix`
3. **Relay SNI/passthrough** -- Support both old and new validation flows
4. **Relay rotation** -- Return `integration_secrets` map
5. **Kotlin MuxFrame** -- Add hostname payload to `Open` frame
6. **Kotlin CertificateStore** -- Add `storeIntegrationSecrets` / `getIntegrationSecrets`
7. **Kotlin RelayApiClient** -- Update response types
8. **Kotlin OnboardingFlow** -- Store integration secrets
9. **Kotlin SessionHandler** -- Route by integration from OPEN hostname
10. **Kotlin McpRouting** -- Remove path-based `{integration}` routing, use root paths
11. **Kotlin OAuthMetadata** -- Remove integration path from URLs
12. **App UrlBuilder** -- Build per-integration hostnames
13. **App UI** -- Display new URL format per integration
14. **Cleanup** -- Remove legacy `secret_prefix` after migration window

---

### Critical Files for Implementation

- `relay/src/firestore.rs` -- Schema change is the foundation; `integration_secrets` field in `DeviceRecord`
- `relay/src/passthrough.rs` -- Secret validation logic changes from single prefix to map lookup with integration resolution
- `relay/src/api/register.rs` -- Registration must generate and return per-integration secrets map
- `core/mcp/src/commonMain/kotlin/com/rousecontext/mcp/core/McpRouting.kt` -- Largest client-side change: remove path-based `{integration}` routing, simplify to root-level routes
- `core/bridge/src/commonMain/kotlin/com/rousecontext/bridge/SessionHandler.kt` -- Must resolve integration from OPEN frame hostname and route to correct McpSession
