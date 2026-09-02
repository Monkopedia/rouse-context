#!/usr/bin/env bash
# Fails if `runBlocking` appears in any production Kotlin source tree (#136).
#
# Production code should be runBlocking-free: it blocks a thread until the
# coroutine completes, which on Android means an ANR when it happens on the main
# thread and a starved dispatcher when it happens anywhere else. Tests and the
# debug-only ADB harness may use it freely -- neither is in the scanned set.
# After #152 collapsed TlsAcceptor's java.io bridge to suspend-native channels,
# the tunnel module no longer needs an exclusion either.
#
# The scanned trees come from scripts/production-source-dirs.sh, which is shared
# with the #379 sensitive-logging gate. Do not hard-code a path list here: the
# two gates having separate hand-maintained lists is what caused #547.
#
# KDoc/comment lines beginning with "* " are permitted, so prose about
# runBlocking does not trip the gate.

set -euo pipefail

# Assigned, then used. Inside `cd "$(git rev-parse --show-toplevel)"` the
# status of `git rev-parse` was discarded and only `cd` decided what happened
# next -- and `cd ""` is a bash error whose message ("null directory") says
# nothing about the real cause. As an assignment the failure stops the script
# with git's own explanation, which is what a human reading a red CI log needs.
repo_root=$(git rev-parse --show-toplevel)
cd "$repo_root"

# Via a variable, not `mapfile < <(...)`: process substitution does not
# propagate its exit status, so a failing preflight would leave DIRS empty and
# this script would exit 0 having scanned nothing -- the exact silent-pass shape
# #547 is about. Command substitution under `set -e` aborts here instead.
dirs=$(bash scripts/production-source-dirs.sh)
mapfile -t DIRS <<<"$dirs"
[ "${#DIRS[@]}" -gt 0 ] || { echo "ERROR: no production source dirs to scan" >&2; exit 1; }

# No `2>/dev/null` and no blanket `|| true`: a grep that cannot read a tree must
# be a red build, not a silent pass. grep exits 1 for "no matches" (the good
# case) and >1 for a real error.
set +e
raw=$(grep -rn '\brunBlocking\b' --include='*.kt' "${DIRS[@]}")
status=$?
set -e
if [ "$status" -gt 1 ]; then
  echo "ERROR: grep failed with status $status (see stderr above)." >&2
  echo "The gate did not scan the tree; treat this as a failure, not a pass." >&2
  exit 1
fi

# Drop block-comment continuation lines (` * ...`) so prose about runBlocking does
# not fail the build. Anchored to the start of grep's `file:line:content` output
# and applied only to the content field: an unanchored `grep -v ':[[:space:]]*\*'`
# also swallowed real violations whose content happened to contain `: *` anywhere
# (#590, the same defect as #577 in the sibling sensitive-logging gate).
matches=$(printf '%s\n' "$raw" | grep -vE '^[^:]*:[0-9]+:[[:space:]]*\*' || true)

if [ -n "$matches" ]; then
  echo "Found production runBlocking (issue #136 regression):"
  echo "$matches"
  echo
  echo "Make the caller suspend, or hoist the call into an existing coroutine"
  echo "scope. See .claude/rules/coroutines.md."
  exit 1
fi

echo "OK: no production runBlocking across ${#DIRS[@]} source sets"
