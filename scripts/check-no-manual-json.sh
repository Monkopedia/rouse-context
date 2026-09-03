#!/usr/bin/env bash
set -euo pipefail

# Reject manual-template JSON construction in production Kotlin code.
#
# Per #417: """{...}""" with field-interpolation produces malformed JSON when
# values contain quotes, backslashes, or control chars. Use @Serializable
# data classes + Json.encodeToString instead. The harness assertion in #426
# catches runtime cases at test time; this static check prevents reintroduction
# at review time.
#
# Allowlist:
#   - app/src/main/.../AuditDetailScreen.kt — @Preview Compose fixtures only.
#   - Lines containing the marker `// allow-manual-json: <reason>`.
#
# Usage: bash scripts/check-no-manual-json.sh

# ADDING A NEW GATE? It MUST ship a matching scripts/tests/<gate>-test.sh, wired
# into CI. The harness -- not the shared filter below -- is what catches an
# unanchored post-filter; see the header of scripts/lib/gate-filters.sh.
#
# Resolved from this script's own location rather than from the working
# directory, so the gate can be invoked by path from anywhere -- including from
# the throwaway repo its self-test builds.
gate_lib_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=scripts/lib/gate-filters.sh
source "$gate_lib_dir/lib/gate-filters.sh"

# git grep -n emits `path:line:content`. The AuditDetailScreen.kt allowlist is a
# PATH filter, so it goes through `exempt_path`, which anchors it to the path
# field and requires the basename to match exactly. scripts/lib/gate-filters.sh
# documents both halves and what the unanchored form let through (#594 -- third
# instance of #577/#590).
#
# The `allow-manual-json` filter above it is a genuine content-field marker and
# needs no anchoring — a path cannot contain `//` — so it deliberately stays a
# plain `grep -v`. The self-test asserts it still works.
#
# `set +e` rather than a trailing `|| true`: both discard the status, and every
# stage here exits 1 when it simply has nothing left to emit (the clean case),
# but the `||` form puts a function in a condition and turns set -e off inside it
# (SC2310).
set +e
hits=$(git grep -nE '"""\{' -- '*/src/main/*.kt' \
    | grep -v -E '//\s*allow-manual-json' \
    | exempt_path AuditDetailScreen.kt)
set -e

if [ -n "$hits" ]; then
    cat <<EOF >&2
Manual-template JSON construction found in production code.
Per #417, this risks producing malformed JSON when interpolated values contain
quotes, backslashes, or control characters. Use @Serializable + Json.encodeToString.

If you have a legitimate non-runtime use (e.g. a UI preview fixture), add
"// allow-manual-json: <reason>" on the line.

$hits
EOF
    exit 1
fi
echo "No manual-template JSON in production code."
