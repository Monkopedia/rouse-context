#!/usr/bin/env bash
# One-time VPS setup for the Rouse Context relay server.
# Run as root on a fresh Debian/Ubuntu VPS.
set -euo pipefail

echo "=== Rouse Context Relay — VPS Setup ==="

# Create service user
useradd --system --no-create-home --shell /usr/sbin/nologin relay || true

# Create directories
mkdir -p /opt/rouse-relay
mkdir -p /etc/rouse-relay/tls
mkdir -p /var/log/rouse-relay

# Set ownership
chown relay:relay /opt/rouse-relay
chown relay:relay /etc/rouse-relay
chown -R relay:relay /var/log/rouse-relay

# Install certbot with Cloudflare DNS plugin (for relay's own cert)
apt-get update
apt-get install -y certbot python3-certbot-dns-cloudflare

# Create certbot credentials file (operator fills in the token)
cat > /etc/rouse-relay/cloudflare.ini <<'CLOUDFLARE'
# Cloudflare API token with DNS:Edit permission for rousecontext.com
dns_cloudflare_api_token = REPLACE_ME
CLOUDFLARE
chmod 600 /etc/rouse-relay/cloudflare.ini

# Create env file template
cat > /etc/rouse-relay/env <<'ENV'
# Cloudflare API token (DNS edit for rousecontext.com zone)
CLOUDFLARE_API_TOKEN=
CLOUDFLARE_ZONE_ID=

# Firebase (not needed if using service account JSON file)
# FIREBASE_PROJECT_ID=rouse-context

# Admin alerts (optional)
ADMIN_ALERT_WEBHOOK=
ENV
chmod 600 /etc/rouse-relay/env

# Install systemd service
cp /opt/rouse-relay/relay.service /etc/systemd/system/rouse-relay.service
systemctl daemon-reload
systemctl enable rouse-relay

echo ""
echo "=== Setup complete ==="
echo ""
echo "Next steps:"
echo "  1. Edit /etc/rouse-relay/env with your secrets"
echo "  2. Edit /etc/rouse-relay/cloudflare.ini with your Cloudflare API token"
echo "  3. Place firebase-sa.json at /etc/rouse-relay/firebase-sa.json"
echo "  4. Run: certbot certonly --dns-cloudflare --dns-cloudflare-credentials /etc/rouse-relay/cloudflare.ini -d relay.rousecontext.com"
echo "  5. Copy cert to /etc/rouse-relay/tls/relay.pem and key to /etc/rouse-relay/tls/relay-key.pem"
echo "  6. Deploy the relay binary to /opt/rouse-relay/rouse-relay"
echo "  7. Copy relay.toml to /etc/rouse-relay/relay.toml"
echo "  8. Run: systemctl start rouse-relay"
