# Security Audit — 2026-04-07

See full findings in the conversation context. Summary:

## Critical (fix immediately)
1. **Missing redirect_uri validation** — OAuth authorize accepts arbitrary URIs, enabling XSS/open redirect
2. **No PKCE format validation** — code_challenge/code_verifier not validated per RFC 7636
3. **Unencrypted notification storage** — 2FA codes, banking info, messages stored plaintext in Room
4. **Plaintext private key storage** — mTLS key in filesDir, should use Android Keystore

## High (fix before production)
5. Insufficient redirect URI validation in /register
6. Rate limiter bypass (global key, not per-integration)
7. No path traversal validation on integration parameter
8. Unencrypted token hash storage in Room
9. No HTTPS enforcement / security headers on OAuth HTML page
10. No rate limiting on /mcp endpoint
11. Weak display code entropy (~41 bits)
12. No origin validation in device code approval

## Medium
13. Exported debug broadcast receiver
14. Intent extras not validated in launch_app
15. Notification action URL scheme validation incomplete
16. Usage integration privacy concerns (detailed app usage exposed)
17. No audit logging for token creation
18. Notification dismissal without user confirmation
19. No certificate pinning on relay connection
20. Predictable stream ID allocation

## Low
21. Audit logging silently fails
22. No CORS validation on OAuth endpoints
23. No token expiration / refresh

## Priority
Week 1: Findings 1-4 (critical input validation + encryption)
Week 2-3: Findings 5-12 (rate limiting, entropy, hardening)
Month 1: Findings 13-23 (defense in depth)
