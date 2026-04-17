# Rouse Context Relay

Rust service that fronts on-device MCP servers running on Android phones. It
performs TLS-passthrough SNI routing, sends FCM wake pushes to the target
device, splices client and device TCP connections, and orchestrates ACME
(Google Trust Services by default; Let's Encrypt also supported) DNS-01
challenges via the Cloudflare API on behalf of devices.

The relay never terminates TLS, never stores payload data, and never handles
device private keys. Device identity keys are generated on-device and never
leave it; the relay only sees CSRs and returns signed certificates.

## Build & Test

```sh
cargo build --release
cargo test
cargo clippy -- -D warnings
cargo fmt --check
```

## Configuration

See `relay.example.toml`. Most fields also accept environment-variable
overrides (see `src/config.rs::apply_env_overrides`).

## Operational notes

### ACME provider

The relay uses Google Trust Services (GTS) as its default ACME provider
(switched from Let's Encrypt in #213). GTS offers ~16,800 new orders/week
vs LE's 50/week/registered-domain, and its roots are in every major
browser/OS trust store, so no client-side change is needed.

#### External Account Binding

GTS requires [External Account Binding](https://datatracker.ietf.org/doc/html/rfc8555#section-7.3.4)
(EAB) on `newAccount`. Create EAB credentials once per project with:

```sh
gcloud publicca external-account-keys create
```

The command returns a `keyId` and a base64url-encoded `b64MacKey`. Store
them on the relay host (for example in `/etc/rouse-relay/env`, loaded via
a systemd `EnvironmentFile=`):

```
GTS_EAB_KID=<keyId>
GTS_EAB_HMAC=<b64MacKey>
```

The env-var names are configurable via `acme.external_account_binding_kid_env`
and `acme.external_account_binding_hmac_env` in `relay.example.toml`.
Leave either unset to fall back to the LE-style no-EAB path.

### ACME account key

The relay persists its ACME account private key at
`/etc/rouse-relay/gts_acme_account_key.pem` (override with
`acme.account_key_path` or the `ACME_ACCOUNT_KEY_PATH` env var). This key
is the relay's identity to the ACME provider. Every certificate issuance
for every device subdomain flows through the single ACME account bound to
this key. The GTS-specific filename means switching providers won't
accidentally reuse an account that belongs to a different directory;
ACME accounts are directory-bound and not portable.

#### Why it must persist across redeploys

Rotating the ACME account does not reset the provider's rate-limit
headroom on the domain, but it does:

- Reset per-account rate-limit history (orphaning any in-flight authorizations
  and re-triggering "new account" checks).
- Leave the prior account registered but unused, with no safe way to clean up
  without full deactivation.
- Eliminate the ability to revoke previously issued certificates via the
  original account.

For the relay, an accidental rotation on every deploy would force every
device subdomain to re-issue under a fresh account's authorization cache,
bypassing the provider's per-account caching; GTS's headroom absorbs this
but LE's 50/week budget would not.

#### Backup

Run `relay/scripts/backup-acme-key.sh` on the relay host to snapshot the key
to a timestamped file:

```sh
./relay/scripts/backup-acme-key.sh
# default: /etc/rouse-relay/acme_account_key.pem
#          -> ~/backups/rouse-relay/acme/<basename>.<UTC-timestamp>

./relay/scripts/backup-acme-key.sh /etc/rouse-relay/gts_acme_account_key.pem /mnt/backups/relay
```

The script creates the destination directory with mode `0700` and writes the
backup file with mode `0600`. It prints the resulting path on stdout.

Scheduling is an operator concern. A simple cron entry is sufficient, e.g.:

```
# Daily at 03:15, keep host-local snapshots.
15 3 * * * /opt/rouse-relay/scripts/backup-acme-key.sh >> /var/log/rouse-relay/acme-backup.log 2>&1
```

Do not push backed-up keys to the repo or any remote store. The key is a
long-lived secret equivalent to the relay's ACME identity.

#### Restore

Stop the relay, copy a backup into place, then start the relay:

```sh
systemctl stop rouse-relay
install -m 0600 /path/to/backup/gts_acme_account_key.pem.<timestamp> \
    /etc/rouse-relay/gts_acme_account_key.pem
chown rouse-relay:rouse-relay /etc/rouse-relay/gts_acme_account_key.pem  # if applicable
systemctl start rouse-relay
```

Confirm the startup log shows `Loaded existing ACME account key` (not
`Generated and saved new ACME account key`). The latter fires at `warn!` with
`acme_account_created = true` and means a fresh account was created; alerting
should trigger on that tag.

#### Startup assertion

Set `acme.require_existing_account = true` (or
`ACME_REQUIRE_EXISTING_ACCOUNT=1`) in production. When enabled, the relay
refuses to start if `acme.account_key_path` is missing, instead of silently
generating a new account. Leave it `false` (the default) on fresh installs
and the first boot of a replacement relay, then flip it on once the key file
exists and is included in the deploy's persistent-state contract.

#### What happens if the key is lost

- The relay generates a new ACME account on next startup (unless
  `require_existing_account` is set).
- Previously issued device certificates remain valid until their natural
  expiry (90 days for GTS and LE). Devices continue working on existing
  certs until renewal.
- Renewals under the new account re-consume the provider's per-account
  rate-limit allocation; GTS absorbs this easily, LE may not.
- There is no way to recover the old ACME account without its private key,
  and no way to deactivate it either.

Prefer restoring from backup over accepting a rotation.
