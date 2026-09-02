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
#   Log.<level>(... "$verifier"         -- PKCE verifier
#   Log.<level>(... "$fcmToken"         -- FCM push token
#   Log.<level>(... "$firebaseToken"    -- Firebase ID token
#   Log.<level>(... "$pkceVerifier"     -- PKCE verifier, spelled out
#   Log.<level>(... "$accessToken"      -- OAuth access token
#   Log.<level>(... "$refreshToken"     -- OAuth refresh token
#   Log.<level>(... "$clientSecret"     -- OAuth client secret
#   Log.<level>(... "$privateKey"       -- key material
#   Log.<level>(... "$apiKey"           -- third-party API key
#   Log.<level>(... "$sessionToken"     -- session bearer credential
#   Log.<level>(... "$integrationSecret" -- per-integration bearer secret
#   Log.<level>(... "$secretPrefix"     -- ditto, the SNI-label prefix form
#   Log.<level>(... "args: $..."        -- tool-call arguments
#
# Each multi-word name is listed in both camelCase and snake_case (`$fcm_token`,
# `$private_key`, `$integration_secret`, ...): the Kotlin sources are camelCase,
# but the relay and the policy doc name these values in snake_case, so that is
# how someone transcribing a value into a log line tends to spell it (#579).
#
# The list is deliberately explicit rather than a substring rule like
# `\$[A-Za-z_]*(secret|token|key)`. Substring matching fires on values
# docs/internal/logging.md says are safe to log -- `$tokenEntity`,
# `TokenEntity.label`, Firebase `$kid` -- and a gate that flags safe lines gets
# worked around instead of heeded. Adding a name here is cheap; keep it that way.
#
# The grep is line-level; multi-line log calls may slip through. The gate is a
# cheap first line of defence; code review is what we rely on for anything it
# misses.

set -euo pipefail

# Assigned, then cd'd, rather than `cd "$(git rev-parse ...)"`. Measured, so the
# reason is the real one and not the obvious guess: the masked form does NOT
# silently pass. `cd ""` is an error in bash, not a no-op, so a failing
# `git rev-parse` did already stop the script. What it stopped with was
# "<script>: line NN: cd: null directory" and exit 1 -- an error attributed to
# the wrong command, on a line whose real problem is that this is not a git work
# tree. As an assignment the failure is attributed to `git`, which has written
# its own "fatal: not a git repository" to stderr, and the script exits with
# git's status (128) instead of a `cd` diagnostic nobody can act on.
repo_root=$(git rev-parse --show-toplevel)
cd "$repo_root"

# Via a variable, not `mapfile < <(...)`: see the note in
# check-no-production-runblocking.sh -- process substitution swallows a failing
# preflight and would leave this script scanning nothing and exiting 0.
dirs=$(bash scripts/production-source-dirs.sh)
mapfile -t DIRS <<<"$dirs"
[ "${#DIRS[@]}" -gt 0 ] || { echo "ERROR: no production source dirs to scan" >&2; exit 1; }

# shellcheck disable=SC2016  # literal regex; `$` must not expand
PATTERN='Log\.[dievw].*\$(token|bearer|verifier|fcmToken|fcm_token|firebaseToken|firebase_token|pkceVerifier|pkce_verifier|accessToken|access_token|refreshToken|refresh_token|clientSecret|client_secret|privateKey|private_key|apiKey|api_key|sessionToken|session_token|integrationSecret|integration_secret|secretPrefix|secret_prefix)\b|Log\.[dievw].*args:[[:space:]]*\$'

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

# Drop block-comment continuation lines (` * ...`) so a pattern list written in a
# Kotlin comment does not fail the build. Anchored to the start of grep's
# `file:line:content` output and applied only to the content field: an unanchored
# `grep -v ':[[:space:]]*\*'` also swallowed real violations whose log string
# happened to contain `: *` anywhere (#577).
matches=$(printf '%s\n' "$raw" | grep -vE '^[^:]*:[0-9]+:[[:space:]]*\*' || true)

if [ -n "$matches" ]; then
  echo "Found sensitive production log sites (issue #379 regression):"
  echo "$matches"
  echo
  echo "See docs/internal/logging.md for how to log these values safely."
  exit 1
fi

echo "OK: no sensitive production log sites across ${#DIRS[@]} source sets"
