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

    #[test]
    fn route_rejects_garbage() {
        let decision = route_connection(b"not a tls handshake", "relay.rousecontext.com");
        assert!(matches!(decision, RouteDecision::Reject));
    }
}
