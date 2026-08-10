#!/usr/bin/env bash
# Fails if known-bad log patterns appear in production Kotlin sources (#379).
# See docs/internal/logging.md for the full policy and rationale.
#
# The scanned trees come from scripts/production-source-dirs.sh, which is shared
# with the #136 runBlocking gate. Do not hard-code a path list here: the two
# gates having separate hand-maintained lists is what caused #547.
#
# Patterns we flag (all look for Kotlin string-template interpolation of a named
# variable, so literal uses like "rotate-secret" as an endpoint name are not
# false positives):
#   Log.<level>(... "$token"            -- logging a token variable
#   Log.<level>(... "$bearer"           -- ditto
#   Log.<level>(... "$fcmToken"         -- FCM push token
#   Log.<level>(... "$firebaseToken"    -- Firebase ID token
#   Log.<level>(... "$verifier"         -- PKCE verifier
#   Log.<level>(... "$accessToken"      -- OAuth access token
#   Log.<level>(... "$refreshToken"     -- OAuth refresh token
#   Log.<level>(... "$clientSecret"     -- OAuth client secret
#   Log.<level>(... "$privateKey"       -- key material
#   Log.<level>(... "args: $..."        -- tool-call arguments
#
# The grep is line-level; multi-line log calls may slip through. The gate is a
# cheap first line of defence; code review is what we rely on for anything it
# misses.

set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

# Via a variable, not `mapfile < <(...)`: see the note in
# check-no-production-runblocking.sh -- process substitution swallows a failing
# preflight and would leave this script scanning nothing and exiting 0.
dirs=$(bash scripts/production-source-dirs.sh)
mapfile -t DIRS <<<"$dirs"
[ "${#DIRS[@]}" -gt 0 ] || { echo "ERROR: no production source dirs to scan" >&2; exit 1; }

# shellcheck disable=SC2016  # literal regex; `$` must not expand
PATTERN='Log\.[dievw].*\$(token|bearer|fcmToken|firebaseToken|verifier|accessToken|refreshToken|clientSecret|privateKey|pkceVerifier)\b|Log\.[dievw].*args:[[:space:]]*\$'

# No `2>/dev/null` and no blanket `|| true`: see the note in
# check-no-production-runblocking.sh. grep exits 1 for "no matches" and >1 for a
# real error.
set +e
raw=$(grep -rnE "$PATTERN" --include='*.kt' "${DIRS[@]}")
status=$?
set -e
if [ "$status" -gt 1 ]; then
  echo "ERROR: grep failed with status $status (see stderr above)." >&2
  echo "The gate did not scan the tree; treat this as a failure, not a pass." >&2
  exit 1
fi

matches=$(printf '%s\n' "$raw" | grep -v ':[[:space:]]*\*' || true)

if [ -n "$matches" ]; then
  echo "Found sensitive production log sites (issue #379 regression):"
  echo "$matches"
  echo
  echo "See docs/internal/logging.md for how to log these values safely."
  exit 1
fi

echo "OK: no sensitive production log sites across ${#DIRS[@]} source sets"
