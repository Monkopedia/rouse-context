# Logging Policy

Production log output (both Android `logcat` and the relay's `tracing`
subscriber) is treated as **untrusted sink**:

- `adb logcat` is reachable by anyone with USB access to the device.
- Apps with `READ_LOGS` on older Android releases can read all of it.
- The relay's logs are mixed into operator-visible output (journal, stdout)
  and may be shipped to hosted log stacks.

The audit behind issue #379 enumerated every production log site and
classified each as clean / scrub / debug-only. This file captures the
resulting policy so new log lines stay safe.

## Never log

These values, in full or prefix, regardless of log level:

- **OAuth credentials:** bearer tokens, refresh tokens, authorization codes,
  PKCE verifiers, client secrets.
- **FCM push tokens.** They let an attacker replay wake events against the
  relay. A short prefix is still identifying.
- **Private keys** of any form: ACME account keys, device identity key,
  TLS cert private keys. (Hardware-backed keys can't be exported anyway --
  this is about not accidentally logging signing output or PKCS#8 dumps.)
- **Per-integration secrets** (`integration_secret` on the relay; also
  `secret_prefix`). These are bearer credentials an AI client presents in
  the SNI label to route to a specific integration.
- **Health Connect record payloads** -- individual data points, ranges,
  anything tool queries actually return.
- **Tool-call `arguments`** -- arbitrary JSON provided by the caller; may
  contain PII, prompts, search queries, API keys pasted by the user.
- **Tool-call `result`s** -- same risk, plus the health/notification
  payloads mentioned above.
- **MCP request bodies** and **HTTP request/response bodies** anywhere in
  the pipeline.

## Safe to log

- **Structural events**: `"Tool call received: get_steps"` (tool name OK;
  args not). `"Tunnel connected"`. `"Mux stream opened sid=42"`.
- **Error types without payloads**: `"TLS handshake failed:
  SSLHandshakeException"`. The exception's class + message is fine; its
  request body or key material is not.
- **Counts, lengths, durations as numbers**: `"Received N-byte body"`,
  `"Token refresh took 42ms"`, `"${token.length} chars"`.
- **User-facing identifiers** like `TokenEntity.label` / OAuth `client_name`
  ("Claude", "ChatGPT"). These are AI-client labels the user already sees
  in the UI.
- **Subdomains on the relay**: `abc123.rousecontext.com` is the relay's
  public routing identity for a device. It's operational metadata the relay
  is the authority on, so the relay logs it at `info` for routing / ops
  visibility. On the device, prefer not to log it.
- **Firebase key IDs (`kid`)**: public metadata on the JWKS endpoint.

## How to replace a sensitive log

Typical rewrites:

- `Log.d(TAG, "Received tool call args: $args")`
  -> `Log.d(TAG, "Received tool call args: ${args.length} chars")`
  or just `"Received tool call"` if the length is not useful.
- `Log.d(TAG, "Token refreshed: $token")`
  -> `Log.d(TAG, "Token refreshed for client=$clientLabel")`
  (label OK; token not).
- `Log.d(TAG, "FCM token: $token")`
  -> `Log.d(TAG, "FCM token updated (len=${token.length})")`, or remove
  the line if the length is not diagnostic.
- `Log.d(TAG, "PKCE verifier: $verifier")` -> delete, or replace with
  `"PKCE verifier generated"`.
- Rust: `tracing::info!("...{integration_secret}...")` -> don't. If you
  need the variant of an enum whose `Debug` would leak, write a
  `fn _log(val: &T) -> String` helper that renders only the safe fields.

When you scrub a previously sensitive line, leave a one-line inline comment
pointing at this doc and issue #379 so the provenance is obvious:

```kotlin
// scrub: previously logged $args (see #379)
Log.d(TAG, "Tool call received")
```

Don't litter the codebase with these comments -- only add one when the
new log line would look arbitrary without the history.

## Adding new log calls

Before committing a new `Log.*`, `tracing::*!`, `println!`, or similar:

1. Would a user reading `adb logcat` on my dev device see values from
   this line that they wouldn't see in the UI? If yes, scrub.
2. Would an attacker who captured this line gain an ability they don't
   have from the device identifier alone? If yes, scrub.
3. Is the value part of an HTTP or MCP body the relay/app is processing
   on behalf of the user? If yes, scrub -- log its type, size, or outcome
   instead.

## Regression protection

`.github/workflows/ci.yml` greps production Kotlin sources for known-bad
patterns on every PR:

- `Log.*(\$token|\$bearer|\$fcmToken|\$firebaseToken|\$verifier|\$accessToken|\$refreshToken)`
- `Log.*(args: \$|secret[^s]|private[_ ]?key)`

This is a cheap first line of defence; code review is what we rely on for
anything the grep misses (multi-line logs, fields-by-format, etc.). If a
legitimate log line trips the gate, fix the log line first -- don't loosen
the grep. The patterns are tight enough that false positives are rare.

For Rust, the same rules apply. The relay has no grep-level gate yet; PR
review covers it.
