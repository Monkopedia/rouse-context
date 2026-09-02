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
    # The `|| true` is deliberate, and it is not a way to reach green. This is
    # the diagnostic half of a failure that has ALREADY been decided -- the
    # `exit 1` below is the verdict -- and the only thing that matters here is
    # that the reason reaches stderr first. Hoisting `id -un` into an assignment
    # would let a failing `id` kill this script BEFORE it says why it is
    # unhappy: exit 1 with no output, which reads as a crash rather than as the
    # finding it is (#628). The username is a courtesy; losing the diagnosis to
    # protect it would be the wrong trade on a script that guards ACME key
    # material. (`|| true` and not `|| echo unknown`: measured, only the
    # literal `|| true` / `||:` forms satisfy SC2312 -- so on a failing `id`
    # the name comes out empty, which the quotes above make readable.)
    echo "error: source ACME key at ${SRC} is not readable by user" \
        "'$(id -un || true)'" >&2
    exit 1
fi

mkdir -p "${DEST_DIR}"
chmod 0700 "${DEST_DIR}"

TIMESTAMP="$(date -u +%Y%m%d-%H%M%SZ)"
DEST="${DEST_DIR}/$(basename "${SRC}").${TIMESTAMP}"

cp -p "${SRC}" "${DEST}"
chmod 0600 "${DEST}"

echo "${DEST}"
