//! RFC 8555 wire types for ACME.
//!
//! These mirror the JSON structures returned by ACME servers. All fields we do
//! not inspect are `#[serde(default)]` so future extensions do not break us.

use base64::Engine;

/// Base64url encoding without padding, as required by ACME/JWS.
pub(super) fn base64url(data: &[u8]) -> String {
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(data)
}

/// ACME directory endpoints, discovered from the directory URL.
#[derive(Debug, Clone, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct AcmeDirectory {
    pub new_nonce: String,
    pub new_account: String,
    pub new_order: String,
}

/// ACME order object.
#[derive(Debug, Clone, serde::Deserialize)]
#[allow(dead_code)]
pub(super) struct AcmeOrder {
    pub status: String,
    pub authorizations: Vec<String>,
    pub finalize: String,
    #[serde(default)]
    pub certificate: Option<String>,
    /// Let's Encrypt includes these but we don't need them.
    #[serde(default)]
    pub expires: Option<String>,
    #[serde(default)]
    pub identifiers: Option<serde_json::Value>,
    #[serde(default)]
    pub not_before: Option<String>,
    #[serde(default)]
    pub not_after: Option<String>,
}

/// ACME identifier object (e.g. {"type": "dns", "value": "example.com"}).
#[derive(Debug, Clone, serde::Deserialize)]
#[allow(dead_code)]
pub(super) struct AcmeIdentifier {
    #[serde(rename = "type")]
    pub identifier_type: String,
    pub value: String,
}

/// ACME authorization object.
#[derive(Debug, Clone, serde::Deserialize)]
#[allow(dead_code)]
pub(super) struct AcmeAuthorization {
    pub status: String,
    #[serde(default)]
    pub challenges: Vec<AcmeChallenge>,
    /// The identifier this authorization is for.
    #[serde(default)]
    pub identifier: Option<AcmeIdentifier>,
    /// Expiry timestamp (ISO 8601).
    #[serde(default)]
    pub expires: Option<String>,
    /// Whether this is a wildcard authorization.
    #[serde(default)]
    pub wildcard: Option<bool>,
}

/// ACME challenge object.
#[derive(Debug, Clone, serde::Deserialize)]
#[allow(dead_code)]
pub(super) struct AcmeChallenge {
    #[serde(rename = "type")]
    pub challenge_type: String,
    pub url: String,
    pub token: String,
    #[serde(default)]
    pub status: Option<String>,
    /// Error detail from ACME server when challenge fails.
    #[serde(default)]
    pub error: Option<serde_json::Value>,
    /// Timestamp when challenge was validated (ISO 8601).
    #[serde(default)]
    pub validated: Option<String>,
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Real Let's Encrypt authorization response with all fields present.
    const LETSENCRYPT_AUTHORIZATION: &str = r#"{
        "status": "pending",
        "expires": "2026-04-11T12:00:00Z",
        "identifier": {
            "type": "dns",
            "value": "abc123.rousecontext.com"
        },
        "challenges": [
            {
                "type": "http-01",
                "status": "pending",
                "url": "https://acme-v02.api.letsencrypt.org/acme/chall-v3/123/http",
                "token": "http-token-abc"
            },
            {
                "type": "dns-01",
                "status": "pending",
                "url": "https://acme-v02.api.letsencrypt.org/acme/chall-v3/123/dns",
                "token": "dns-token-xyz"
            },
            {
                "type": "tls-alpn-01",
                "status": "pending",
                "url": "https://acme-v02.api.letsencrypt.org/acme/chall-v3/123/tls",
                "token": "tls-token-def"
            }
        ]
    }"#;

    /// Authorization with a validated challenge (includes extra `validated` field).
    const VALIDATED_AUTHORIZATION: &str = r#"{
        "status": "valid",
        "expires": "2026-04-11T12:00:00Z",
        "identifier": {
            "type": "dns",
            "value": "abc123.rousecontext.com"
        },
        "challenges": [
            {
                "type": "dns-01",
                "status": "valid",
                "url": "https://acme-v02.api.letsencrypt.org/acme/chall-v3/123/dns",
                "token": "dns-token-xyz",
                "validated": "2026-04-04T10:30:00Z"
            }
        ]
    }"#;

    /// Minimal authorization with only required fields.
    const MINIMAL_AUTHORIZATION: &str = r#"{
        "status": "pending",
        "challenges": [
            {
                "type": "dns-01",
                "url": "https://acme-v02.api.letsencrypt.org/acme/chall-v3/456/dns",
                "token": "minimal-token"
            }
        ]
    }"#;

    /// Authorization with challenge error detail.
    const FAILED_AUTHORIZATION: &str = r#"{
        "status": "invalid",
        "identifier": {
            "type": "dns",
            "value": "abc123.rousecontext.com"
        },
        "challenges": [
            {
                "type": "dns-01",
                "status": "invalid",
                "url": "https://acme-v02.api.letsencrypt.org/acme/chall-v3/789/dns",
                "token": "bad-token",
                "error": {
                    "type": "urn:ietf:params:acme:error:dns",
                    "detail": "DNS problem: NXDOMAIN looking up TXT for _acme-challenge.abc123.rousecontext.com",
                    "status": 400
                }
            }
        ]
    }"#;

    /// Real Let's Encrypt order response.
    const LETSENCRYPT_ORDER: &str = r#"{
        "status": "pending",
        "expires": "2026-04-11T12:00:00Z",
        "identifiers": [
            {"type": "dns", "value": "abc123.rousecontext.com"}
        ],
        "authorizations": [
            "https://acme-v02.api.letsencrypt.org/acme/authz-v3/123"
        ],
        "finalize": "https://acme-v02.api.letsencrypt.org/acme/finalize/456/789"
    }"#;

    /// Order with certificate URL (ready to download).
    const COMPLETED_ORDER: &str = r#"{
        "status": "valid",
        "expires": "2026-04-11T12:00:00Z",
        "identifiers": [
            {"type": "dns", "value": "abc123.rousecontext.com"}
        ],
        "authorizations": [
            "https://acme-v02.api.letsencrypt.org/acme/authz-v3/123"
        ],
        "finalize": "https://acme-v02.api.letsencrypt.org/acme/finalize/456/789",
        "certificate": "https://acme-v02.api.letsencrypt.org/acme/cert/abc123"
    }"#;

    #[test]
    fn parse_letsencrypt_authorization() {
        let auth: AcmeAuthorization =
            serde_json::from_str(LETSENCRYPT_AUTHORIZATION).expect("should parse");
        assert_eq!(auth.status, "pending");
        assert_eq!(auth.challenges.len(), 3);
        assert_eq!(
            auth.identifier.as_ref().unwrap().value,
            "abc123.rousecontext.com"
        );
        assert_eq!(auth.expires.as_deref(), Some("2026-04-11T12:00:00Z"));

        let dns = auth
            .challenges
            .iter()
            .find(|c| c.challenge_type == "dns-01")
            .expect("should have dns-01");
        assert_eq!(dns.token, "dns-token-xyz");
        assert_eq!(dns.status.as_deref(), Some("pending"));
    }

    #[test]
    fn parse_validated_authorization() {
        let auth: AcmeAuthorization =
            serde_json::from_str(VALIDATED_AUTHORIZATION).expect("should parse");
        assert_eq!(auth.status, "valid");

        let dns = &auth.challenges[0];
        assert_eq!(dns.status.as_deref(), Some("valid"));
        assert_eq!(dns.validated.as_deref(), Some("2026-04-04T10:30:00Z"));
    }

    #[test]
    fn parse_minimal_authorization() {
        let auth: AcmeAuthorization =
            serde_json::from_str(MINIMAL_AUTHORIZATION).expect("should parse");
        assert_eq!(auth.status, "pending");
        assert!(auth.identifier.is_none());
        assert!(auth.expires.is_none());
        assert!(auth.wildcard.is_none());
        assert_eq!(auth.challenges[0].status, None);
    }

    #[test]
    fn parse_failed_authorization_with_error() {
        let auth: AcmeAuthorization =
            serde_json::from_str(FAILED_AUTHORIZATION).expect("should parse");
        assert_eq!(auth.status, "invalid");

        let dns = &auth.challenges[0];
        assert!(dns.error.is_some());
        let err = dns.error.as_ref().unwrap();
        assert!(err["detail"].as_str().unwrap().contains("NXDOMAIN"));
    }

    #[test]
    fn parse_letsencrypt_order() {
        let order: AcmeOrder = serde_json::from_str(LETSENCRYPT_ORDER).expect("should parse");
        assert_eq!(order.status, "pending");
        assert_eq!(order.authorizations.len(), 1);
        assert!(order.certificate.is_none());
        assert!(order.expires.is_some());
        assert!(order.identifiers.is_some());
    }

    #[test]
    fn parse_completed_order() {
        let order: AcmeOrder = serde_json::from_str(COMPLETED_ORDER).expect("should parse");
        assert_eq!(order.status, "valid");
        assert_eq!(
            order.certificate.as_deref(),
            Some("https://acme-v02.api.letsencrypt.org/acme/cert/abc123")
        );
    }

    /// Ensure we tolerate unknown fields from future ACME extensions.
    #[test]
    fn parse_authorization_with_unknown_fields() {
        let json = r#"{
            "status": "pending",
            "challenges": [],
            "someNewField": true,
            "anotherFutureField": {"nested": "value"}
        }"#;
        let auth: AcmeAuthorization =
            serde_json::from_str(json).expect("should ignore unknown fields");
        assert_eq!(auth.status, "pending");
    }

    #[test]
    fn parse_challenge_with_unknown_fields() {
        let json = r#"{
            "type": "dns-01",
            "url": "https://example.com/chall",
            "token": "abc",
            "status": "pending",
            "futureField": 42
        }"#;
        let challenge: AcmeChallenge =
            serde_json::from_str(json).expect("should ignore unknown fields");
        assert_eq!(challenge.challenge_type, "dns-01");
    }
}
