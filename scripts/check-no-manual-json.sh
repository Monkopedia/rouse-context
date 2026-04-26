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

hits=$(git grep -nE '"""\{' -- '*/src/main/*.kt' \
    | grep -v -E '//\s*allow-manual-json' \
    | grep -v 'AuditDetailScreen.kt' \
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
