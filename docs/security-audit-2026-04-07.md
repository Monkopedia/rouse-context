# Security Audit — 2026-04-07

See full findings in the conversation context. Summary:

> **Status update (2026-04-24, per #406 chunk A audit against `origin/main` @ `632add27`):**
> Of the 23 findings below, **15 are FIXED**, **6 are ACCEPTED-AS-RISK** (5 documented in
> `security-decisions.md`; #12 effectively-accepted by analogous reasoning to #22), **1 is PARTIAL**
> (#8 — tokens stored as SHA-256 hashes, not encryption), and **3 are UNKNOWN** (#17, #18, #21 —
> need closer review). Per-finding annotations are inline below.

## Critical (fix immediately)
1. **Missing redirect_uri validation** — OAuth authorize accepts arbitrary URIs, enabling XSS/open redirect
   - **STATUS: FIXED.** http/https-only validation enforced in `core/mcp/.../McpRouting.kt` on both `/register` (lines 317–338) and `/authorize` (lines 439–448).
2. **No PKCE format validation** — code_challenge/code_verifier not validated per RFC 7636
   - **STATUS: FIXED.** RFC 7636 length + charset checks and S256-only verification in `core/mcp/.../AuthorizationCodeManager.kt:50-52,164-176,408`.
3. **Unencrypted notification storage** — 2FA codes, banking info, messages stored plaintext in Room
   - **STATUS: FIXED.** AES-GCM with Android Keystore key in `notifications/src/main/java/com/rousecontext/notifications/FieldEncryptor.kt`.
4. **Plaintext private key storage** — mTLS key in filesDir, should use Android Keystore
   - **STATUS: FIXED (issue #200).** StrongBox/TEE-backed P-256 keypair, alias `rouse_device_key`; key never leaves secure element. See `app/src/main/java/com/rousecontext/app/cert/AndroidKeystoreDeviceKeyManager.kt:18-44`.

## High (fix before production)
5. Insufficient redirect URI validation in /register
   - **STATUS: FIXED.** Same code path as #1 (the OAuth DCR `/register`; relay `/register` has no `redirect_uri`).
6. Rate limiter bypass (global key, not per-integration)
   - **STATUS: FIXED.** Per-integration rate-limit keys (`"$ri/register"`, `"$ri/authorize"`, `"$ri/mcp"`, etc.) in `core/mcp/.../McpRouting.kt:293,391,417,616-617`.
7. No path traversal validation on integration parameter
   - **STATUS: FIXED.** Integration is now derived from the first DNS label and gated by registry lookup; `resolveIntegration()` and `registry.providerForPath(ri)` return 404 for unknown ids (`core/mcp/.../McpRouting.kt:191-199`). Integration is no longer a URL path segment.
8. Unencrypted token hash storage in Room
   - **STATUS: PARTIAL.** Tokens are stored as SHA-256 hashes — plaintext is never persisted (`app/src/main/java/com/rousecontext/app/token/RoomTokenStore.kt:22,170-174`). Practically equivalent protection (offline attacker recovers no usable token), but the literal audit recommendation (encrypt the hash table itself) is not done. Reconsider if the threat model requires.
9. No HTTPS enforcement / security headers on OAuth HTML page
   - **STATUS: FIXED.** `X-Frame-Options: DENY`, `Content-Security-Policy`, `Strict-Transport-Security` set on `/authorize` (`core/mcp/.../McpRouting.kt:472-482`); CSP coverage tested in `AuthPageCspTest.kt`.
10. No rate limiting on /mcp endpoint
    - **STATUS: FIXED.** `mcpRateLimiter.tryAcquire("$ri/mcp")` in `core/mcp/.../McpRouting.kt:616-617`.
11. Weak display code entropy (~41 bits)
    - **STATUS: FIXED.** 12 chars from a 31-char alphabet (no I/L/O/0/1) → ~59 bits (`core/mcp/.../DeviceCodeManager.kt:37,191-202`).
12. No origin validation in device code approval
    - **STATUS: ACCEPTED-AS-RISK (effectively).** Device-code `approve(userCode)` (`DeviceCodeManager.kt:144`) is gated by the on-device user typing the code, so origin doesn't apply. Authorization-code `getStatus(request_id)` uses a 128-bit UUID (`AuthorizationCodeManager.kt:184`) — same logic as #22 in `security-decisions.md`.

## Medium
13. Exported debug broadcast receiver
    - **STATUS: ACCEPTED-AS-RISK.** Debug-only build flavor; see `security-decisions.md` ("Debug broadcast receiver exported (Audit #13)").
14. Intent extras not validated in launch_app
    - **STATUS: ACCEPTED-AS-RISK.** Within OAuth-approved trust boundary; see `security-decisions.md` ("Intent extras in launch_app (Audit #14)").
15. Notification action URL scheme validation incomplete
    - **STATUS: FIXED.** `open_link` validates `scheme == http or https` in `integrations/.../OutreachMcpProvider.kt:161-172`. Resolves the "needs verification" note in `security-decisions.md`.
16. Usage integration privacy concerns (detailed app usage exposed)
    - **STATUS: OPEN (UX backlog).** Tracked in `security-decisions.md` as "address via UX clear setup copy".
17. No audit logging for token creation
    - **STATUS: UNKNOWN — needs review.** The audit listener (`notifications/.../audit/RoomAuditListener.kt`) records MCP tool calls; could not confirm a token-issuance hook fires into the same audit table. Verify whether token issuance produces an audit row.
18. Notification dismissal without user confirmation
    - **STATUS: UNKNOWN — needs review.** Not surveyed in this pass; check `NotificationMcpProvider` tools for dismissal flow.
19. No certificate pinning on relay connection
    - **STATUS: ACCEPTED-AS-RISK.** Incompatible with no-auto-update model; see `security-decisions.md` ("Certificate pinning on relay connection (Audit #19)").
20. Predictable stream ID allocation
    - **STATUS: ACCEPTED-AS-RISK.** Security model doesn't depend on ID unpredictability; see `security-decisions.md` ("Sequential stream IDs (Audit #20)").

## Low
21. Audit logging silently fails
    - **STATUS: UNKNOWN — needs review.** Did not locate a `try/catch` that swallows audit failures; closer look at `RoomAuditListener` error paths required.
22. No CORS validation on OAuth endpoints
    - **STATUS: ACCEPTED-AS-RISK.** Same-origin polling; `request_id` is 128-bit. See `security-decisions.md` ("CORS on OAuth endpoints (Audit #22)").
23. No token expiration / refresh
    - **STATUS: FIXED.** `expiresAt`, `refreshExpiresAt`, `rotatedAt` on `app/src/main/java/com/rousecontext/app/token/TokenEntity.kt:36-46`; OAuth 2.1 §4.14 refresh-token rotation with family revocation on reuse in `RoomTokenStore.kt:115-140`. (`security-decisions.md` updated to remove the stale "Not yet implemented" line.)

## Priority
Week 1: Findings 1-4 (critical input validation + encryption)
Week 2-3: Findings 5-12 (rate limiting, entropy, hardening)
Month 1: Findings 13-23 (defense in depth)
