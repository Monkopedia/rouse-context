# Self-Hosting Rouse Context

Rouse Context is designed to be fork-friendly. If you want to run your own deployment — under your own domain, your own VPS, your own Firebase project — this guide walks through the full setup.

The project is parameterized such that a single Gradle property (`-Pdomain=yourdomain.example`) and a matching relay config drive every hostname in the system.

## What you need

1. A domain you own (registered anywhere). Example: `coolapp.example`.
2. A Cloudflare account with that domain on it (DNS).
3. A Firebase project (FCM push + Firebase Auth anonymous sign-in).
4. A small VPS to run the relay (1 GB RAM is enough).
5. Android Studio / JDK 21 for building the app.
6. Rust toolchain (for cross-compiling the relay, ideally locally — never build on the VPS).

## 1. Domain + DNS

Point your domain's nameservers at Cloudflare. Inside Cloudflare, create the zone.

DNS records you'll need (you can create them up front, but the relay creates the per-device CNAMEs dynamically):

| Record | Type | Target | Notes |
|---|---|---|---|
| `relay.coolapp.example` | A | VPS IPv4 | Relay API + wake endpoint |
| `*.coolapp.example` wildcard | -- | -- | Optional; the relay manages `_acme-challenge` TXT records and per-device subdomains automatically. |

Create a Cloudflare API token with **DNS:Edit** scope, restricted to the one zone. Save the token — you'll set it as an env var on the VPS.

Note the zone ID from the Cloudflare dashboard (the hex string on the zone's overview page).

## 2. Firebase

Create a Firebase project. Enable:

- **Authentication** — Anonymous sign-in (used for per-device identity).
- **Cloud Messaging** (FCM) — for waking devices on demand.
- **Firestore** — for device records (subdomain ↔ FCM token mapping, cert expiry, etc.).

Then:

- Register an Android app in the project with package name `com.rousecontext.debug` (debug) and `com.rousecontext` (release). You may want to rename the package — see "Package name" below. Download `google-services.json` and place it at `app/google-services.json`.
- Generate a service account key with the **Firebase Admin SDK** and **Cloud Messaging** roles. Download as JSON; copy it to the VPS (e.g. `/etc/rouse-relay/firebase-sa.json`). The relay reads it directly.

## 3. Signing keystore

Do **not** reuse the repo's `debug.keystore` or `release.keystore` — those are committed to this repo and specific to the upstream project. Generate your own:

```bash
keytool -genkeypair -v \
    -keystore .signing/debug.keystore \
    -storepass YOUR_DEBUG_PASS \
    -keypass YOUR_DEBUG_PASS \
    -alias debug \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug, O=YourOrg, C=US"

keytool -genkeypair -v \
    -keystore .signing/release.keystore \
    -storepass YOUR_RELEASE_PASS \
    -keypass YOUR_RELEASE_PASS \
    -alias release \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Release, O=YourOrg, C=US"
```

Update `app/build.gradle.kts` signing config blocks to match your passwords, or (better) read them from environment variables.

**Back these up off-machine.** Regenerating a keystore forces users to uninstall (app-data loss) and burns ACME cert quota on re-registration.

## 4. Android build

All hostnames derive from a single Gradle property, `-Pdomain`:

```bash
./gradlew :app:assembleDebug -Pdomain=coolapp.example
```

The app's `BuildConfig` will have:

- `BASE_DOMAIN = "coolapp.example"`
- `RELAY_HOST = "relay.coolapp.example"` (derived as `"relay." + baseDomain`, unless you also pass `-Prelay.host=...`)

Test the override wired correctly:

```bash
./gradlew -Pdomain=coolapp.example :app:compileDebugKotlin
```

For production/release builds, use the same flag with `:app:assembleRelease` and your release keystore.

### Package name

The Android package (`com.rousecontext.*`) is **not** parameterized. If you want to distribute your fork on the Play Store or sideload without namespace collisions, rename the application ID in `app/build.gradle.kts`:

```kotlin
defaultConfig {
    applicationId = "com.yourorg.yourapp"
    // ...
}
```

This is invasive because Kotlin package paths use the same identifier. The simpler alternative is to leave the code packages alone but change `applicationId` only — Android permits this.

## 5. Relay build + deploy

Never build on the VPS. Cross-compile locally, or use GitHub Actions. Example with `cross`:

```bash
cd relay
# For a typical x86_64 Linux VPS:
cross build --release --target x86_64-unknown-linux-gnu
# Binary ends up at target/x86_64-unknown-linux-gnu/release/rouse-relay
scp target/x86_64-unknown-linux-gnu/release/rouse-relay user@vps:/usr/local/bin/
```

### Config

Copy `relay/relay.example.toml` to `/etc/rouse-relay/relay.toml` on the VPS and edit:

```toml
[server]
bind_addr = "0.0.0.0:443"
relay_hostname = "relay.coolapp.example"
# base_domain is optional; derived from relay_hostname by stripping "relay."
# Set explicitly if your relay hostname doesn't follow that convention:
# base_domain = "coolapp.example"

[tls]
cert_path = "/etc/rouse-relay/cert.pem"
key_path = "/etc/rouse-relay/key.pem"

[firebase]
project_id = "your-firebase-project-id"
service_account_path = "/etc/rouse-relay/firebase-sa.json"

[cloudflare]
zone_id = "your-cloudflare-zone-id"
api_token_env = "CF_API_TOKEN"

[acme]
directory_url = "https://acme-v02.api.letsencrypt.org/directory"
dns_propagation_timeout_secs = 60
dns_poll_interval_secs = 5
account_key_path = "/etc/rouse-relay/acme_account_key.pem"

[device_ca]
ca_key_path = "/etc/rouse-relay/device_ca_key.pem"
ca_cert_path = "/etc/rouse-relay/device_ca_cert.pem"

[limits]
max_streams_per_device = 8
wake_rate_limit = 6
ws_ping_interval_secs = 30
ws_read_timeout_secs = 60
stale_device_sweep_days = 180
```

Environment variable overrides are also supported: `RELAY_HOSTNAME`, `RELAY_BASE_DOMAIN`, `RELAY_TLS_CERT_PATH`, `RELAY_FIREBASE_PROJECT_ID`, `RELAY_CF_ZONE_ID`, etc. See `relay/src/config.rs` for the complete list.

### Device CA + TLS cert

The relay needs:

- A **device CA** (self-signed) — signs per-device client certificates for mTLS. Generate once:
  ```bash
  openssl req -x509 -newkey rsa:2048 -nodes \
      -keyout /etc/rouse-relay/device_ca_key.pem \
      -out /etc/rouse-relay/device_ca_cert.pem \
      -subj "/CN=Rouse Device CA" \
      -days 3650
  ```
- A **TLS cert for the relay hostname** (`relay.coolapp.example`). Get this from Let's Encrypt or your preferred CA. The relay's ACME machinery is for *device* wildcard certs, not the relay's own cert — that's a separate bootstrap step.

### systemd unit

```ini
# /etc/systemd/system/rouse-relay.service
[Unit]
Description=Rouse Context Relay
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=relay
ExecStart=/usr/local/bin/rouse-relay /etc/rouse-relay/relay.toml
Environment=CF_API_TOKEN=your_cloudflare_token_here
Environment=ACME_CONTACT=ops@coolapp.example
Restart=on-failure
RestartSec=5
AmbientCapabilities=CAP_NET_BIND_SERVICE
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/etc/rouse-relay /var/lib/rouse-relay

[Install]
WantedBy=multi-user.target
```

Enable and start:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now rouse-relay
sudo journalctl -u rouse-relay -f
```

### ACME bootstrap

On first device registration, the relay registers an ACME account and persists the account key at `acme.account_key_path`. Subsequent devices reuse the account.

If you want to pre-register before any device hits the relay, you can generate an account key manually and let the relay discover it, but the lazy path works fine.

## 6. Verifying the deployment

1. Install the app on an Android device, signed with your release keystore.
2. Open the app, complete onboarding. The app registers with the relay, receives a subdomain, and provisions client/server certs.
3. Enable an integration (e.g. Health Connect).
4. Copy the integration URL (form: `https://brave-falcon.<subdomain>.coolapp.example/mcp`).
5. Add it to an MCP client (Claude, Cursor, etc.).
6. The client connects → relay wakes the phone via FCM → phone serves the MCP session directly over end-to-end TLS.

If any step fails, tail the relay logs (`journalctl -u rouse-relay -f`) and the device's logcat (`adb logcat | grep -i rouse`).

## Troubleshooting

**DNS TXT records not propagating** — check that the Cloudflare API token has `DNS:Edit` on the correct zone. The relay logs the exact DNS record it's trying to create.

**Device cert registration fails with 403** — verify `google-services.json` in the app matches the Firebase project configured in the relay's service account, and that Anonymous sign-in is enabled.

**`strip_prefix("relay.")` doesn't match my hostname** — set `[server].base_domain` explicitly in `relay.toml`.

**ACME rate limit (50 certs/week)** — use the staging directory (`https://acme-staging-v02.api.letsencrypt.org/directory`) for development, and persist `acme.account_key_path` so you don't register new accounts on every restart.
