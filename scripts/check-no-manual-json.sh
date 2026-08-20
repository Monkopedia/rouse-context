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

# git grep -n emits `path:line:content`. The allowlist below is a PATH filter,
# so it is anchored to the path field: `^([^:]*/)?` cannot cross the first colon,
# and the trailing `:[0-9]+:` pins that colon to the path/line separator. Without
# that anchoring it also matched the content field, so any production line that
# merely mentioned the exempt file in a comment or string silently dropped out of
# the results (#594 — third instance of #577/#590). Requiring the basename to be
# exactly AuditDetailScreen.kt also keeps longer names that contain it
# (AuditDetailScreenTest.kt, MyAuditDetailScreen.kt) inside the gate.
#
# The `allow-manual-json` filter above it is a genuine content-field marker and
# needs no anchoring: a path cannot contain `//`.
hits=$(git grep -nE '"""\{' -- '*/src/main/*.kt' \
    | grep -v -E '//\s*allow-manual-json' \
    | grep -v -E '^([^:]*/)?AuditDetailScreen\.kt:[0-9]+:' \
    || true)

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
