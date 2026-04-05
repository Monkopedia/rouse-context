use rouse_relay::sni::{parse_sni, RouteDecision};

/// Build a minimal TLS 1.2+ ClientHello with the given SNI hostname.
/// This constructs just enough of the handshake to be parseable.
fn build_client_hello(hostname: &str) -> Vec<u8> {
    let hostname_bytes = hostname.as_bytes();

    // SNI extension: type(2) + list_len(2) + entry_type(1) + name_len(2) + name
    let sni_entry_len = 1 + 2 + hostname_bytes.len();
    let sni_list_len = sni_entry_len;
    let sni_ext_data_len = 2 + sni_list_len; // list_length(2) + list
    let sni_ext_len = 2 + 2 + sni_ext_data_len; // type(2) + length(2) + data

    // Extensions block
    let extensions_len = sni_ext_len;

    // ClientHello body (after handshake header):
    // version(2) + random(32) + session_id_len(1) + cipher_suites_len(2) + one_suite(2) +
    // compression_len(1) + null_compression(1) + extensions_length(2) + extensions
    let client_hello_body_len = 2 + 32 + 1 + 2 + 2 + 1 + 1 + 2 + extensions_len;

    // Handshake header: type(1) + length(3)
    let handshake_len = 1 + 3 + client_hello_body_len;

    // TLS record: type(1) + version(2) + length(2) + handshake
    let mut buf = Vec::with_capacity(5 + handshake_len);

    // TLS record header
    buf.push(0x16); // ContentType: Handshake
    buf.push(0x03);
    buf.push(0x01); // TLS 1.0 record version (normal for ClientHello)
    let record_len = handshake_len as u16;
    buf.extend_from_slice(&record_len.to_be_bytes());

    // Handshake header
    buf.push(0x01); // HandshakeType: ClientHello
    let ch_len = client_hello_body_len as u32;
    buf.push((ch_len >> 16) as u8);
    buf.push((ch_len >> 8) as u8);
    buf.push(ch_len as u8);

    // ClientHello body
    buf.push(0x03);
    buf.push(0x03); // TLS 1.2

    // Random (32 bytes of zeros)
    buf.extend_from_slice(&[0u8; 32]);

    // Session ID length: 0
    buf.push(0);

    // Cipher suites: length 2, one suite (TLS_AES_128_GCM_SHA256)
    buf.extend_from_slice(&[0x00, 0x02]);
    buf.extend_from_slice(&[0x13, 0x01]);

    // Compression methods: length 1, null
    buf.push(0x01);
    buf.push(0x00);

    // Extensions length
    buf.extend_from_slice(&(extensions_len as u16).to_be_bytes());

    // SNI extension
    buf.extend_from_slice(&[0x00, 0x00]); // extension type: server_name
    buf.extend_from_slice(&(sni_ext_data_len as u16).to_be_bytes());
    buf.extend_from_slice(&(sni_list_len as u16).to_be_bytes());
    buf.push(0x00); // host_name type
    buf.extend_from_slice(&(hostname_bytes.len() as u16).to_be_bytes());
    buf.extend_from_slice(hostname_bytes);

    buf
}

#[test]
fn extract_sni_from_client_hello() {
    let hello = build_client_hello("cool-penguin.rousecontext.com");
    let sni = parse_sni(&hello).unwrap();
    assert_eq!(sni, "cool-penguin.rousecontext.com");
}

#[test]
fn extract_sni_different_hostname() {
    let hello = build_client_hello("relay.rousecontext.com");
    let sni = parse_sni(&hello).unwrap();
    assert_eq!(sni, "relay.rousecontext.com");
}

#[test]
fn no_sni_returns_none() {
    // Minimal ClientHello with no extensions
    let mut buf = Vec::new();
    // TLS record header
    buf.push(0x16);
    buf.push(0x03);
    buf.push(0x01);
    let body_len: u16 = 4 + 2 + 32 + 1 + 2 + 2 + 1 + 1;
    buf.extend_from_slice(&body_len.to_be_bytes());
    // Handshake header
    buf.push(0x01);
    let ch_len = body_len - 4;
    buf.push(0);
    buf.push((ch_len >> 8) as u8);
    buf.push(ch_len as u8);
    // Version
    buf.push(0x03);
    buf.push(0x03);
    // Random
    buf.extend_from_slice(&[0u8; 32]);
    // Session ID len
    buf.push(0);
    // Cipher suites
    buf.extend_from_slice(&[0x00, 0x02, 0x13, 0x01]);
    // Compression
    buf.push(0x01);
    buf.push(0x00);
    // No extensions

    let sni = parse_sni(&buf);
    assert!(sni.is_none());
}

#[test]
fn truncated_data_returns_none() {
    let sni = parse_sni(&[0x16, 0x03, 0x01]);
    assert!(sni.is_none());
}

#[test]
fn non_handshake_returns_none() {
    let mut hello = build_client_hello("example.com");
    hello[0] = 0x17; // Change from Handshake to ApplicationData
    let sni = parse_sni(&hello);
    assert!(sni.is_none());
}

#[test]
fn relay_hostname_routes_to_api() {
    let decision =
        RouteDecision::from_sni(Some("relay.rousecontext.com"), "relay.rousecontext.com");
    assert!(matches!(decision, RouteDecision::RelayApi));
}

#[test]
fn device_subdomain_routes_to_passthrough() {
    let decision = RouteDecision::from_sni(
        Some("cool-penguin.rousecontext.com"),
        "relay.rousecontext.com",
    );
    match decision {
        RouteDecision::DevicePassthrough { subdomain } => {
            assert_eq!(subdomain, "cool-penguin");
        }
        other => panic!("Expected DevicePassthrough, got {:?}", other),
    }
}

#[test]
fn unknown_sni_closes_connection() {
    let decision = RouteDecision::from_sni(Some("evil.otherdomain.com"), "relay.rousecontext.com");
    assert!(matches!(decision, RouteDecision::Reject));
}

#[test]
fn no_sni_closes_connection() {
    let decision = RouteDecision::from_sni(None, "relay.rousecontext.com");
    assert!(matches!(decision, RouteDecision::Reject));
}

#[test]
fn bare_domain_routes_to_api() {
    let decision = RouteDecision::from_sni(Some("rousecontext.com"), "relay.rousecontext.com");
    // Bare domain without relay prefix should reject -- it's not the relay hostname
    // and not a valid subdomain pattern
    assert!(matches!(decision, RouteDecision::Reject));
}
