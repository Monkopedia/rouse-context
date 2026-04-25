# Security Decisions Log

Tracks security concerns we've evaluated and decided not to address, with reasoning.
Only remove entries from this list if we later implement a fix.

## Accepted Risks

### Debug broadcast receiver exported (Audit #13)
**Decision:** Accept
**Reasoning:** `TestWakeReceiver` is in `app/src/debug/` and excluded from release builds entirely. Debug builds are only on developer devices. Not a production risk.

### Intent extras in launch_app (Audit #14)
**Decision:** Accept
**Reasoning:** The MCP client has already been through full OAuth approval. If the user has granted an AI client access to launch apps, passing string extras is within that trust boundary. The user explicitly approved this integration.

### Certificate pinning on relay connection (Audit #19)
**Decision:** Skip
**Reasoning:** Without an automatic app update channel, cert pinning would break all devices when the relay cert rotates (Let's Encrypt = 90 days). The attack surface is narrow (attacker needs a valid cert for `relay.rousecontext.com` from a public CA). mTLS client cert already authenticates device→relay. System CA store validates relay→device. Revisit if we add auto-update.

### Sequential stream IDs (Audit #20)
**Decision:** Accept
**Reasoning:** Security through obscurity is not security. The relay's `dispatch_incoming` routes frames by stream ID to the sender that owns that stream. A device can only send DATA/CLOSE for streams the relay opened for it. Frames for unowned streams are silently dropped. The security model doesn't depend on stream ID unpredictability.

### CORS on OAuth endpoints (Audit #22)
**Decision:** Accept
**Reasoning:** The authorize status polling endpoint is same-origin (served from the device's own hostname). Cross-origin access would require the attacker to know the request_id (128-bit random UUID). The timing information leaked (whether a user approved) is low-value.

## Addressed (for reference)

### Token expiration / refresh (Audit #23)
**Decision:** Implement refresh tokens (short-lived access + long-lived refresh) with rotation.
**Status:** Implemented. `app/src/main/java/com/rousecontext/app/token/TokenEntity.kt` carries `expiresAt`, `refreshExpiresAt`, and `rotatedAt`; `app/src/main/java/com/rousecontext/app/token/RoomTokenStore.kt` (see `refreshToken`, lines ~115–140) performs OAuth 2.1 §4.14 refresh-token rotation with family-wide revocation on detected reuse.

### Notification action URLs (Audit #15)
**Decision:** Verify http/https validation covers notification action buttons too.
**Status:** Verified. `open_link` in `integrations/.../OutreachMcpProvider.kt` (around lines 161–172) rejects any scheme other than `http` or `https`.

### Usage integration privacy (Audit #16)
**Decision:** Address via UX — clear setup copy explaining what's exposed
**Status:** Backlog item.
