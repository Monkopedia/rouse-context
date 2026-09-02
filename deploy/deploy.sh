#!/usr/bin/env bash
# Deploy the relay binary to the VPS.
# Usage: ./deploy.sh [user@host]
set -euo pipefail

REMOTE="${1:-relay@relay.rousecontext.com}"
BINARY="relay/target/x86_64-unknown-linux-musl/release/rouse-relay"

echo "=== Building relay (musl static binary) ==="
cd relay
cargo build --release --target x86_64-unknown-linux-musl
cd ..

if [ ! -f "$BINARY" ]; then
    echo "Error: binary not found at $BINARY"
    echo "Make sure you have the musl target installed: rustup target add x86_64-unknown-linux-musl"
    exit 1
fi

# Assigned, then printed: substituted straight into the `echo`, the status of
# `du` (and of `cut`) is thrown away, so a binary that became unreadable between
# the existence check above and here would print "Binary size:  " and this
# script would go on to `scp` it to production anyway -- measured against this
# script with a `du` stubbed to fail: it printed "Binary size:  ", copied the
# binary, restarted the unit and exited 0. As an assignment the
# pipeline's status is `set -e`-visible -- and `du` has already written its own
# reason to stderr, so the deploy stops WITH a diagnosis rather than the
# exit-1-in-silence shape (#628).
binary_size=$(du -h "$BINARY" | cut -f1)
echo "=== Binary size: $binary_size ==="

echo "=== Deploying to $REMOTE ==="
scp "$BINARY" "$REMOTE":/opt/rouse-relay/rouse-relay.new
scp deploy/relay.toml.production "$REMOTE":/etc/rouse-relay/relay.toml
scp deploy/relay.service "$REMOTE":/tmp/relay.service

ssh "$REMOTE" bash -s <<'REMOTE_SCRIPT'
set -euo pipefail
sudo mv /opt/rouse-relay/rouse-relay.new /opt/rouse-relay/rouse-relay
sudo chmod +x /opt/rouse-relay/rouse-relay
sudo cp /tmp/relay.service /etc/systemd/system/rouse-relay.service
sudo systemctl daemon-reload
sudo systemctl restart rouse-relay
sleep 2
sudo systemctl status rouse-relay --no-pager
echo "=== Deploy complete ==="
REMOTE_SCRIPT
