//! SNI-based connection routing.
//!
//! The router reads the first bytes of an incoming TCP connection to extract
//! the SNI hostname from the TLS ClientHello, then decides how to handle it:
//!
//! - **Relay API**: SNI matches `relay_hostname` -- terminate TLS and handle
//!   HTTP requests (wake endpoint, ACME orchestration).
//! - **Device passthrough**: SNI is `<subdomain>.rousecontext.com` -- pure TLS
//!   passthrough. The relay NEVER terminates TLS for device connections.
//! - **Reject**: Unknown or missing SNI -- close the connection.

use crate::sni::{parse_sni, RouteDecision};

/// Route an incoming connection based on the initial bytes (TLS ClientHello).
///
/// Returns the routing decision and the buffered bytes (which must be forwarded
/// since they've already been read from the socket).
pub fn route_connection(initial_bytes: &[u8], relay_hostname: &str) -> RouteDecision {
    let sni = parse_sni(initial_bytes);
    RouteDecision::from_sni(sni.as_deref(), relay_hostname)
}

#[cfg(test)]
mod tests {
    use super::*;

    const RELAY_HOST: &str = "relay.rousecontext.com";

    /// Build a minimal TLS ClientHello containing the given SNI hostname.
    ///
    /// This constructs just enough of a real ClientHello for `parse_sni` to
    /// extract the hostname. It is NOT a valid TLS handshake in the broader
    /// sense -- cipher suites and compression are stubs.
    fn build_client_hello(sni: &str) -> Vec<u8> {
        // SNI extension payload: type(1) + name_len(2) + name
        let sni_bytes = sni.as_bytes();
        let sni_entry_len = 1 + 2 + sni_bytes.len(); // name_type + name_len + name
        let sni_list_len = sni_entry_len;
        let sni_ext_data_len = 2 + sni_list_len; // list_len(2) + list

        let mut sni_ext = Vec::new();
        // Extension type: SNI = 0x0000
        sni_ext.extend_from_slice(&0x0000u16.to_be_bytes());
        // Extension data length
        sni_ext.extend_from_slice(&(sni_ext_data_len as u16).to_be_bytes());
        // Server name list length
        sni_ext.extend_from_slice(&(sni_list_len as u16).to_be_bytes());
        // Name type: host_name = 0x00
        sni_ext.push(0x00);
        // Name length
        sni_ext.extend_from_slice(&(sni_bytes.len() as u16).to_be_bytes());
        // Name
        sni_ext.extend_from_slice(sni_bytes);

        // Extensions total
        let extensions_len = sni_ext.len();

        // ClientHello body
        let mut hello = Vec::new();
        // Client version: TLS 1.2
        hello.extend_from_slice(&[0x03, 0x03]);
        // Random: 32 bytes of zeros
        hello.extend_from_slice(&[0u8; 32]);
        // Session ID length: 0
        hello.push(0x00);
        // Cipher suites: length=2, one suite (TLS_RSA_WITH_AES_128_GCM_SHA256)
        hello.extend_from_slice(&[0x00, 0x02, 0x00, 0x9c]);
        // Compression methods: length=1, null
        hello.extend_from_slice(&[0x01, 0x00]);
        // Extensions length
        hello.extend_from_slice(&(extensions_len as u16).to_be_bytes());
        // Extensions
        hello.extend_from_slice(&sni_ext);

        // Handshake header
        let mut handshake = Vec::new();
        // Handshake type: ClientHello = 0x01
        handshake.push(0x01);
        // Length (3 bytes)
        let hello_len = hello.len();
        handshake.push(((hello_len >> 16) & 0xFF) as u8);
        handshake.push(((hello_len >> 8) & 0xFF) as u8);
        handshake.push((hello_len & 0xFF) as u8);
        handshake.extend_from_slice(&hello);

        // TLS record header
        let mut record = Vec::new();
        // Content type: Handshake = 0x16
        record.push(0x16);
        // Version: TLS 1.0 (record layer always says 1.0)
        record.extend_from_slice(&[0x03, 0x01]);
        // Record length
        let hs_len = handshake.len();
        record.extend_from_slice(&(hs_len as u16).to_be_bytes());
        record.extend_from_slice(&handshake);

        record
    }

    /// Build a minimal ClientHello with NO extensions at all.
    fn build_client_hello_no_extensions() -> Vec<u8> {
        let mut hello = Vec::new();
        hello.extend_from_slice(&[0x03, 0x03]); // version
        hello.extend_from_slice(&[0u8; 32]); // random
        hello.push(0x00); // session ID length
        hello.extend_from_slice(&[0x00, 0x02, 0x00, 0x9c]); // cipher suites
        hello.extend_from_slice(&[0x01, 0x00]); // compression

        let mut handshake = Vec::new();
        handshake.push(0x01);
        let hello_len = hello.len();
        handshake.push(((hello_len >> 16) & 0xFF) as u8);
        handshake.push(((hello_len >> 8) & 0xFF) as u8);
        handshake.push((hello_len & 0xFF) as u8);
        handshake.extend_from_slice(&hello);

        let mut record = Vec::new();
        record.push(0x16);
        record.extend_from_slice(&[0x03, 0x01]);
        let hs_len = handshake.len();
        record.extend_from_slice(&(hs_len as u16).to_be_bytes());
        record.extend_from_slice(&handshake);

        record
    }

    // ── Garbage / malformed input ──────────────────────────────────────

    #[test]
    fn route_rejects_garbage() {
        let decision = route_connection(b"not a tls handshake", RELAY_HOST);
        assert!(matches!(decision, RouteDecision::Reject));
    }

    #[test]
    fn route_rejects_empty_input() {
        let decision = route_connection(b"", RELAY_HOST);
        assert!(matches!(decision, RouteDecision::Reject));
    }

    #[test]
    fn route_rejects_truncated_record_header() {
        // Only 4 bytes -- TLS record header needs 5
        let decision = route_connection(&[0x16, 0x03, 0x01, 0x00], RELAY_HOST);
        assert!(matches!(decision, RouteDecision::Reject));
    }

    #[test]
    fn route_rejects_non_handshake_record_type() {
        // Content type 0x17 = Application Data, not Handshake
        let decision = route_connection(&[0x17, 0x03, 0x01, 0x00, 0x05, 0, 0, 0, 0, 0], RELAY_HOST);
        assert!(matches!(decision, RouteDecision::Reject));
    }

    // ── Missing SNI ────────────────────────────────────────────────────

    #[test]
    fn route_rejects_client_hello_without_sni() {
        let hello = build_client_hello_no_extensions();
        let decision = route_connection(&hello, RELAY_HOST);
        assert!(matches!(decision, RouteDecision::Reject));
    }

    // ── Relay API routing ──────────────────────────────────────────────

    #[test]
    fn route_relay_hostname_to_api() {
        let hello = build_client_hello("relay.rousecontext.com");
        let decision = route_connection(&hello, RELAY_HOST);
        assert_eq!(decision, RouteDecision::RelayApi);
    }

    // ── Device passthrough routing ─────────────────────────────────────

    #[test]
    fn route_known_device_pattern_to_passthrough() {
        let hello = build_client_hello("brave-health.cool-penguin.rousecontext.com");
        let decision = route_connection(&hello, RELAY_HOST);
        assert_eq!(
            decision,
            RouteDecision::DevicePassthrough {
                subdomain: "cool-penguin".to_string(),
                integration_secret: "brave-health".to_string(),
            }
        );
    }

    // ── Unknown / bare subdomains ──────────────────────────────────────

    #[test]
    fn route_rejects_bare_subdomain_without_secret() {
        // "cool-penguin.rousecontext.com" has no integration secret prefix
        let hello = build_client_hello("cool-penguin.rousecontext.com");
        let decision = route_connection(&hello, RELAY_HOST);
        assert_eq!(decision, RouteDecision::Reject);
    }

    #[test]
    fn route_rejects_unknown_domain() {
        let hello = build_client_hello("something.example.com");
        let decision = route_connection(&hello, RELAY_HOST);
        assert_eq!(decision, RouteDecision::Reject);
    }

    #[test]
    fn route_rejects_too_many_subdomain_levels() {
        // Three levels of subdomain: extra.secret.device.rousecontext.com
        let hello = build_client_hello("extra.secret.device.rousecontext.com");
        let decision = route_connection(&hello, RELAY_HOST);
        assert_eq!(decision, RouteDecision::Reject);
    }

    #[test]
    fn route_rejects_bare_base_domain() {
        let hello = build_client_hello("rousecontext.com");
        let decision = route_connection(&hello, RELAY_HOST);
        assert_eq!(decision, RouteDecision::Reject);
    }

    #[test]
    fn route_rejects_relay_as_subdomain() {
        // "relay.rousecontext.com" is the relay itself, but if someone
        // somehow sends "relay" as a bare subdomain it should still route
        // to RelayApi (handled by exact match), not passthrough.
        let hello = build_client_hello("relay.rousecontext.com");
        let decision = route_connection(&hello, RELAY_HOST);
        assert_eq!(decision, RouteDecision::RelayApi);
    }

    // ── Edge cases ─────────────────────────────────────────────────────

    #[test]
    fn route_handles_hyphenated_names() {
        let hello = build_client_hello("swift-outreach.bright-owl.rousecontext.com");
        let decision = route_connection(&hello, RELAY_HOST);
        assert_eq!(
            decision,
            RouteDecision::DevicePassthrough {
                subdomain: "bright-owl".to_string(),
                integration_secret: "swift-outreach".to_string(),
            }
        );
    }

    #[test]
    fn route_handles_test_prefixed_subdomains() {
        // Debug builds use test- prefix (#438)
        let hello = build_client_hello("brave-health.test-cool-penguin.rousecontext.com");
        let decision = route_connection(&hello, RELAY_HOST);
        assert_eq!(
            decision,
            RouteDecision::DevicePassthrough {
                subdomain: "test-cool-penguin".to_string(),
                integration_secret: "brave-health".to_string(),
            }
        );
    }
}
