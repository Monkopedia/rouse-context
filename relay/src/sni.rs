/// Parse the SNI hostname from a TLS ClientHello message.
///
/// Returns `None` if the data is not a valid ClientHello or lacks an SNI extension.
/// This parser is deliberately minimal -- it extracts only the SNI hostname and
/// does not validate the rest of the handshake.
pub fn parse_sni(buf: &[u8]) -> Option<String> {
    // TLS record header: type(1) + version(2) + length(2)
    if buf.len() < 5 {
        return None;
    }
    // Must be Handshake content type (0x16)
    if buf[0] != 0x16 {
        return None;
    }
    let record_len = u16::from_be_bytes([buf[3], buf[4]]) as usize;
    let record = buf.get(5..5 + record_len)?;

    // Handshake header: type(1) + length(3)
    if record.len() < 4 {
        return None;
    }
    // Must be ClientHello (0x01)
    if record[0] != 0x01 {
        return None;
    }
    let handshake_len =
        ((record[1] as usize) << 16) | ((record[2] as usize) << 8) | (record[3] as usize);
    let hello = record.get(4..4 + handshake_len)?;

    // ClientHello: version(2) + random(32) + session_id_len(1) + ...
    if hello.len() < 35 {
        return None;
    }
    let mut pos = 34; // skip version + random
    let session_id_len = *hello.get(pos)? as usize;
    pos += 1 + session_id_len;

    // Cipher suites: length(2) + suites
    if pos + 2 > hello.len() {
        return None;
    }
    let cipher_len = u16::from_be_bytes([hello[pos], hello[pos + 1]]) as usize;
    pos += 2 + cipher_len;

    // Compression methods: length(1) + methods
    if pos >= hello.len() {
        return None;
    }
    let comp_len = hello[pos] as usize;
    pos += 1 + comp_len;

    // Extensions: length(2) + extension data
    if pos + 2 > hello.len() {
        return None;
    }
    let ext_total_len = u16::from_be_bytes([hello[pos], hello[pos + 1]]) as usize;
    pos += 2;

    let ext_end = pos + ext_total_len;
    while pos + 4 <= ext_end && pos + 4 <= hello.len() {
        let ext_type = u16::from_be_bytes([hello[pos], hello[pos + 1]]);
        let ext_len = u16::from_be_bytes([hello[pos + 2], hello[pos + 3]]) as usize;
        pos += 4;

        if ext_type == 0x0000 {
            // SNI extension
            return parse_sni_extension(hello.get(pos..pos + ext_len)?);
        }
        pos += ext_len;
    }

    None
}

fn parse_sni_extension(data: &[u8]) -> Option<String> {
    if data.len() < 2 {
        return None;
    }
    let list_len = u16::from_be_bytes([data[0], data[1]]) as usize;
    let list = data.get(2..2 + list_len)?;

    let mut pos = 0;
    while pos + 3 <= list.len() {
        let name_type = list[pos];
        let name_len = u16::from_be_bytes([list[pos + 1], list[pos + 2]]) as usize;
        pos += 3;

        if name_type == 0x00 {
            // host_name
            let name_bytes = list.get(pos..pos + name_len)?;
            return String::from_utf8(name_bytes.to_vec()).ok();
        }
        pos += name_len;
    }
    None
}

/// Routing decision based on SNI hostname.
#[derive(Debug, PartialEq)]
pub enum RouteDecision {
    /// Route to the relay's own API (HTTPS).
    RelayApi,
    /// Route to a device via TLS passthrough.
    /// `integration_secret` is the per-integration secret from the SNI label
    /// (e.g. "brave-health" from `brave-health.cool-penguin.rousecontext.com`).
    DevicePassthrough {
        subdomain: String,
        integration_secret: String,
    },
    /// Reject the connection (unknown or missing SNI).
    Reject,
}

impl RouteDecision {
    /// Determine routing based on the extracted SNI hostname and the relay's own hostname.
    ///
    /// Expected device URL format: `{secret_prefix}.{subdomain}.{base_domain}`
    /// e.g. `brave-falcon.cool-penguin.rousecontext.com`
    ///
    /// Bare subdomains without a secret prefix are rejected.
    /// More than two levels of subdomain are rejected.
    pub fn from_sni(sni: Option<&str>, relay_hostname: &str) -> Self {
        let sni = match sni {
            Some(s) => s,
            None => return RouteDecision::Reject,
        };

        if sni == relay_hostname {
            return RouteDecision::RelayApi;
        }

        // Extract the base domain from relay_hostname.
        // relay_hostname = "relay.rousecontext.com" => base = "rousecontext.com"
        let base_domain = relay_hostname
            .strip_prefix("relay.")
            .unwrap_or(relay_hostname);

        // Strip the base domain suffix: "secret.device.rousecontext.com" -> "secret.device"
        if let Some(remainder) = sni.strip_suffix(&format!(".{base_domain}")) {
            if remainder.is_empty() || remainder == "relay" {
                return RouteDecision::Reject;
            }

            // Split remainder on the first dot: "secret.device" -> ("secret", "device")
            if let Some((secret, device)) = remainder.split_once('.') {
                // Reject if device part contains more dots (too many levels)
                if device.contains('.') {
                    return RouteDecision::Reject;
                }
                if !secret.is_empty() && !device.is_empty() {
                    return RouteDecision::DevicePassthrough {
                        subdomain: device.to_string(),
                        integration_secret: secret.to_string(),
                    };
                }
            }
            // No dot in remainder -> bare subdomain without secret prefix -> reject
        }

        RouteDecision::Reject
    }
}
