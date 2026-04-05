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

echo "=== Binary size: $(du -h "$BINARY" | cut -f1) ==="

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
