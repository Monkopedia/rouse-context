# Rouse Context Relay

Rust service that fronts on-device MCP servers running on Android phones. It
performs TLS-passthrough SNI routing, sends FCM wake pushes to the target
device, splices client and device TCP connections, and orchestrates ACME
(Let's Encrypt) DNS-01 challenges via the Cloudflare API on behalf of devices.

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

### ACME account key

The relay persists its ACME account private key at
`/etc/rouse-relay/acme_account_key.pem` (override with `acme.account_key_path`
or the `ACME_ACCOUNT_KEY_PATH` env var). This key is the relay's identity to
Let's Encrypt. Every certificate issuance for every device subdomain flows
through the single ACME account bound to this key.

#### Why it must persist across redeploys

Let's Encrypt enforces a 50-cert/week quota per registered domain, shared
across all issuers for that domain. Rotating the ACME account does not reset
that quota, but it does:

- Reset per-account rate-limit history (orphaning any in-flight authorizations
  and re-triggering "new account" checks).
- Leave the prior account registered but unused, with no safe way to clean up
  without full deactivation.
- Eliminate the ability to revoke previously issued certificates via the
  original account.

For the relay, an accidental rotation on every deploy would also burn the
domain-level 50-certs/week headroom: every device subdomain would need a fresh
issuance under the new account's authorization cache, and the domain-level
budget is shared with all devices.

#### Backup

Run `relay/scripts/backup-acme-key.sh` on the relay host to snapshot the key
to a timestamped file:

```sh
./relay/scripts/backup-acme-key.sh
# default: /etc/rouse-relay/acme_account_key.pem
#          -> ~/backups/rouse-relay/acme/acme_account_key.pem.<UTC-timestamp>

./relay/scripts/backup-acme-key.sh /etc/rouse-relay/acme_account_key.pem /mnt/backups/relay
```

The script creates the destination directory with mode `0700` and writes the
backup file with mode `0600`. It prints the resulting path on stdout.

Scheduling is an operator concern. A simple cron entry is sufficient, e.g.:

```
# Daily at 03:15, keep host-local snapshots.
15 3 * * * /opt/rouse-relay/scripts/backup-acme-key.sh >> /var/log/rouse-relay/acme-backup.log 2>&1
```

Do not push backed-up keys to the repo or any remote store. The key is a
long-lived secret equivalent to the relay's Let's Encrypt identity.

#### Restore

Stop the relay, copy a backup into place, then start the relay:

```sh
systemctl stop rouse-relay
install -m 0600 /path/to/backup/acme_account_key.pem.<timestamp> \
    /etc/rouse-relay/acme_account_key.pem
chown rouse-relay:rouse-relay /etc/rouse-relay/acme_account_key.pem  # if applicable
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
  expiry (90 days for Let's Encrypt). Devices continue working on existing
  certs until renewal.
- Renewals under the new account re-consume the domain-level 50/week budget;
  if renewals bunch up, some devices may see delayed issuance.
- There is no way to recover the old ACME account without its private key,
  and no way to deactivate it either.

Prefer restoring from backup over accepting a rotation.
