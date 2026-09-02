#!/usr/bin/env bash
#
# backup-acme-key.sh
#
# Copy the relay's ACME account key to a host-local backup directory with a
# timestamp suffix. The ACME account key is the relay's identity to the
# ACME provider (GTS by default); losing it forces creation of a fresh
# account, which orphans any in-flight authorizations. See relay/README.md
# for background.
#
# Usage:
#   ./backup-acme-key.sh [src] [dest-dir]
#
# Defaults:
#   src       = /etc/rouse-relay/gts_acme_account_key.pem
#   dest-dir  = ${HOME}/backups/rouse-relay/acme
#
# The script:
#   - Verifies src exists and is readable.
#   - Creates dest-dir with mode 0700 if missing.
#   - Copies src to dest-dir/<basename>.<UTC timestamp>.
#   - Prints the resulting backup path on stdout.
#
# This script is intentionally standalone. Scheduling (cron, systemd timer)
# is an operator concern and is documented in relay/README.md.

set -euo pipefail

SRC="${1:-/etc/rouse-relay/gts_acme_account_key.pem}"
DEST_DIR="${2:-${HOME}/backups/rouse-relay/acme}"

if [[ ! -f "${SRC}" ]]; then
    echo "error: source ACME key not found at ${SRC}" >&2
    exit 1
fi

if [[ ! -r "${SRC}" ]]; then
    # `|| true` deliberately: this is the error path, and the only thing that
    # matters here is that the message reaches stderr before the `exit 1` below.
    # Hoisting `id -un` into an assignment would let a failing `id` kill the
    # script BEFORE it says why it is unhappy -- exit 1 with no output, which
    # reads as a crash rather than as the finding. The username is a courtesy;
    # its absence is not worth losing the diagnosis over.
    echo "error: source ACME key at ${SRC} is not readable by $(id -un || true)" >&2
    exit 1
fi

mkdir -p "${DEST_DIR}"
chmod 0700 "${DEST_DIR}"

TIMESTAMP="$(date -u +%Y%m%d-%H%M%SZ)"
DEST="${DEST_DIR}/$(basename "${SRC}").${TIMESTAMP}"

cp -p "${SRC}" "${DEST}"
chmod 0600 "${DEST}"

echo "${DEST}"
