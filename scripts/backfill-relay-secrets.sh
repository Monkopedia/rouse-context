#!/usr/bin/env bash
#
# backfill-relay-secrets.sh — Re-sync GH repo secrets from the live VPS.
#
# Reads /etc/rouse-relay/env and /etc/rouse-relay/firebase-sa.json from the
# production VPS and populates the corresponding GitHub repository secrets
# consumed by .github/workflows/relay-deploy.yml.
#
# Use this when:
#   - bootstrapping a fork (secrets empty, VPS has the source of truth)
#   - the repo was rotated / recreated and secrets need to be rebuilt
#   - an out-of-band edit was made on the VPS and the workflow needs to match
#     before the next deploy would clobber it
#
# Safety:
#   - set -u -o pipefail (NOT -x; avoid leaking values into set -x traces)
#   - all intermediate files written with mode 0600 under a mode-0700 tmpdir
#   - shred -u on exit (success, failure, or interrupt)
#   - never echoes secret values to stdout; only sizes/counts for sanity
#   - requires explicit "yes" confirmation before mutating GH state
#
# Prereqs:
#   - gcloud configured with access to the `relay` GCE instance
#   - gh authenticated with repo admin rights on the target repo
#
# Usage:
#   scripts/backfill-relay-secrets.sh <owner/repo>
# Example:
#   scripts/backfill-relay-secrets.sh Monkopedia/rouse-context

set -u -o pipefail

REPO="${1:-}"
if [[ -z "$REPO" ]]; then
  echo "Usage: $0 <owner/repo>" >&2
  exit 2
fi

# Values baked in: the production VPS is the `relay` GCE instance in
# us-central1-a. Override via environment for forks.
GCE_INSTANCE="${GCE_INSTANCE:-relay}"
GCE_ZONE="${GCE_ZONE:-us-central1-a}"

echo "About to:"
echo "  1. SSH to GCE instance '${GCE_INSTANCE}' (zone '${GCE_ZONE}')"
echo "  2. Read /etc/rouse-relay/env and /etc/rouse-relay/firebase-sa.json"
echo "  3. Overwrite these GitHub repo secrets on '${REPO}':"
echo "       CLOUDFLARE_API_TOKEN"
echo "       CLOUDFLARE_ZONE_ID"
echo "       RELAY_GTS_EAB_KID"
echo "       RELAY_GTS_EAB_HMAC"
echo "       RELAY_RUST_LOG"
echo "       RELAY_FIREBASE_SERVICE_ACCOUNT_JSON  (base64 of firebase-sa.json)"
echo
read -r -p "Type 'yes' to proceed: " confirm
if [[ "$confirm" != "yes" ]]; then
  echo "Aborted."
  exit 1
fi

# Require tools up front
for cmd in gcloud gh base64 python3; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "Required command not found: $cmd" >&2
    exit 3
  fi
done

# Mode-0700 tempdir; best-effort shred on exit.
WORK=$(mktemp -d)
chmod 700 "$WORK"

cleanup() {
  if [[ -d "$WORK" ]]; then
    find "$WORK" -type f -exec shred -u {} + 2>/dev/null || true
    rm -rf "$WORK"
  fi
}
trap cleanup EXIT INT TERM

# --- 1. Pull env file ---------------------------------------------------------
echo "Reading /etc/rouse-relay/env ..."
gcloud compute ssh "$GCE_INSTANCE" --zone="$GCE_ZONE" \
  --command='sudo cat /etc/rouse-relay/env' \
  > "$WORK/env" 2>/dev/null
chmod 600 "$WORK/env"

# Sanity: file should be non-empty and contain KEY=VALUE lines.
env_lines=$(wc -l < "$WORK/env")
if [[ "$env_lines" -lt 1 ]]; then
  echo "ERROR: /etc/rouse-relay/env came back empty" >&2
  exit 4
fi
echo "  ${env_lines} lines"

# Extract a single KEY's value from the env file into a file, with trailing
# newlines stripped. Never prints the value.
extract_into() {
  local key="$1"
  local out="$2"
  python3 -c "
import sys, re
key = sys.argv[1]
with open(sys.argv[2], 'r') as fh:
    for line in fh:
        m = re.match(rf'^{re.escape(key)}=(.*?)\s*$', line)
        if m:
            val = m.group(1)
            # Strip optional surrounding double quotes (systemd EnvironmentFile quoting)
            if len(val) >= 2 and val[0] == '\"' and val[-1] == '\"':
                val = val[1:-1]
            with open(sys.argv[3], 'w') as o:
                o.write(val)
            sys.exit(0)
    sys.exit(1)
" "$key" "$WORK/env" "$out"
  chmod 600 "$out"
}

extract_into CLOUDFLARE_API_TOKEN "$WORK/cf_api_token" \
  || { echo "ERROR: CLOUDFLARE_API_TOKEN not present in env" >&2; exit 5; }
extract_into CLOUDFLARE_ZONE_ID   "$WORK/cf_zone_id" \
  || { echo "ERROR: CLOUDFLARE_ZONE_ID not present in env" >&2; exit 5; }
extract_into GTS_EAB_KID          "$WORK/gts_eab_kid" \
  || { echo "ERROR: GTS_EAB_KID not present in env" >&2; exit 5; }
extract_into GTS_EAB_HMAC         "$WORK/gts_eab_hmac" \
  || { echo "ERROR: GTS_EAB_HMAC not present in env" >&2; exit 5; }
extract_into RUST_LOG             "$WORK/rust_log" \
  || { echo "WARN: RUST_LOG not present in env; leaving RELAY_RUST_LOG untouched" >&2; rm -f "$WORK/rust_log"; }

# --- 2. Pull firebase service account JSON ------------------------------------
echo "Reading /etc/rouse-relay/firebase-sa.json ..."
gcloud compute ssh "$GCE_INSTANCE" --zone="$GCE_ZONE" \
  --command='sudo cat /etc/rouse-relay/firebase-sa.json' \
  > "$WORK/firebase-sa.json" 2>/dev/null
chmod 600 "$WORK/firebase-sa.json"

# Validate it parses as JSON before uploading it to GH.
python3 -c "import json,sys; json.load(open(sys.argv[1]))" "$WORK/firebase-sa.json" \
  || { echo "ERROR: firebase-sa.json is not valid JSON" >&2; exit 6; }

base64 -w0 "$WORK/firebase-sa.json" > "$WORK/firebase_sa_b64"
chmod 600 "$WORK/firebase_sa_b64"
echo "  $(wc -c < "$WORK/firebase_sa_b64") bytes (base64)"

# --- 3. Push to GH secrets ----------------------------------------------------
push_secret() {
  local name="$1"
  local file="$2"
  if [[ ! -f "$file" ]]; then
    echo "  [skip] $name (no value captured)"
    return 0
  fi
  echo "  [set]  $name ($(wc -c < "$file") bytes)"
  gh secret set "$name" --repo "$REPO" < "$file"
}

echo "Updating GitHub secrets on $REPO ..."
push_secret CLOUDFLARE_API_TOKEN                 "$WORK/cf_api_token"
push_secret CLOUDFLARE_ZONE_ID                   "$WORK/cf_zone_id"
push_secret RELAY_GTS_EAB_KID                    "$WORK/gts_eab_kid"
push_secret RELAY_GTS_EAB_HMAC                   "$WORK/gts_eab_hmac"
push_secret RELAY_RUST_LOG                       "$WORK/rust_log"
push_secret RELAY_FIREBASE_SERVICE_ACCOUNT_JSON  "$WORK/firebase_sa_b64"

echo "Done. Verify with: gh secret list --repo $REPO"
